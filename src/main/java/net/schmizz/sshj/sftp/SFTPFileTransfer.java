/*
 * Copyright (C)2009 - SSHJ Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.schmizz.sshj.sftp;

import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.sftp.Response.StatusCode;
import net.schmizz.sshj.xfer.AbstractFileTransfer;
import net.schmizz.sshj.xfer.FileSystemFile;
import net.schmizz.sshj.xfer.FileTransfer;
import net.schmizz.sshj.xfer.LocalDestFile;
import net.schmizz.sshj.xfer.LocalFileFilter;
import net.schmizz.sshj.xfer.LocalSourceFile;
import net.schmizz.sshj.xfer.TransferListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.List;

public class SFTPFileTransfer
        extends AbstractFileTransfer
        implements FileTransfer {

    private final SFTPEngine engine;

    private volatile LocalFileFilter uploadFilter;
    private volatile RemoteResourceFilter downloadFilter;
    private volatile boolean preserveAttributes = true;

    public SFTPFileTransfer(SFTPEngine engine) {
        this.engine = engine;
    }

    public boolean getPreserveAttributes() {
        return preserveAttributes;
    }

    public void setPreserveAttributes(boolean preserveAttributes) {
        this.preserveAttributes = preserveAttributes;
    }

    @Override
    public void upload(String source, String dest)
            throws IOException {
        upload(new FileSystemFile(source), dest);
    }

    @Override
    public void download(String source, String dest)
            throws IOException {
        download(source, new FileSystemFile(dest));
    }

    @Override
    public void upload(LocalSourceFile localFile, String remotePath)
            throws IOException {
        new Uploader(localFile, remotePath).upload(getTransferListener());
    }

    @Override
    public void download(String source, LocalDestFile dest)
            throws IOException {
        final PathComponents pathComponents = engine.getPathHelper().getComponents(source);
        final FileAttributes attributes = engine.stat(source);
        new Downloader().download(getTransferListener(), new RemoteResourceInfo(pathComponents, attributes), dest);
    }

    public void setUploadFilter(LocalFileFilter uploadFilter) {
        this.uploadFilter = uploadFilter;
    }

    public void setDownloadFilter(RemoteResourceFilter downloadFilter) {
        this.downloadFilter = downloadFilter;
    }

    public LocalFileFilter getUploadFilter() {
        return uploadFilter;
    }

    public RemoteResourceFilter getDownloadFilter() {
        return downloadFilter;
    }

    private class Downloader {

        private void download(final TransferListener listener,
                              final RemoteResourceInfo remote,
                              final LocalDestFile local)
                throws IOException {
            final LocalDestFile adjustedFile;
            switch (remote.getAttributes().getType()) {
                case DIRECTORY:
                    adjustedFile = downloadDir(listener.directory(remote.getName()), remote, local);
                    break;
                case UNKNOWN:
                    log.warn("Server did not supply information about the type of file at `{}` " +
                                     "-- assuming it is a regular file!", remote.getPath());
                case REGULAR:
                    adjustedFile = downloadFile(listener.file(remote.getName(), remote.getAttributes().getSize()),
                                                remote, local);
                    break;
                default:
                    throw new IOException(remote + " is not a regular file or directory");
            }
            if (getPreserveAttributes())
                copyAttributes(remote, adjustedFile);
        }

        private LocalDestFile downloadDir(final TransferListener listener,
                                          final RemoteResourceInfo remote,
                                          final LocalDestFile local)
                throws IOException {
            final LocalDestFile adjusted = local.getTargetDirectory(remote.getName());
            final RemoteDirectory rd = engine.openDir(remote.getPath());
            try {
                for (RemoteResourceInfo rri : rd.scan(getDownloadFilter()))
                    download(listener, rri, adjusted.getChild(rri.getName()));
            } finally {
                rd.close();
            }
            return adjusted;
        }

        private LocalDestFile downloadFile(final StreamCopier.Listener listener,
                                           final RemoteResourceInfo remote,
                                           final LocalDestFile local)
                throws IOException {
            final LocalDestFile adjusted = local.getTargetFile(remote.getName());
            final RemoteFile rf = engine.open(remote.getPath());
            try {
                final RemoteFile.ReadAheadRemoteFileInputStream rfis = rf.new ReadAheadRemoteFileInputStream(16);
                final OutputStream os = adjusted.getOutputStream();
                try {
                    new StreamCopier(rfis, os)
                            .bufSize(engine.getSubsystem().getLocalMaxPacketSize())
                            .keepFlushing(false)
                            .listener(listener)
                            .copy();
                } finally {
                    rfis.close();
                    os.close();
                }
            } finally {
                rf.close();
            }
            return adjusted;
        }

        private void copyAttributes(final RemoteResourceInfo remote, final LocalDestFile local)
                throws IOException {
            final FileAttributes attrs = remote.getAttributes();
            local.setPermissions(attrs.getMode().getPermissionsMask());
            if (attrs.has(FileAttributes.Flag.ACMODTIME)) {
                local.setLastAccessedTime(attrs.getAtime());
                local.setLastModifiedTime(attrs.getMtime());
            }
        }

    }

    private class Uploader {

        private final LocalSourceFile source;
        private final String remote;

        private Uploader(final LocalSourceFile source, final String remote) {
            this.source = source;
            this.remote = remote; //new RemotePath(remote, engine.getPathHelper().getPathSeparator());
        }

        private void upload(final TransferListener listener) throws IOException {
            // This ensures "backwards compatible" behaviour.
            if (source.isDirectory()) {
                if (engine.getPathHelper().getComponents(remote).getName().equals(source.getName())) {
                    makeDirIfNotExists(remote); // Ensure that the directory exists
                    uploadDir(listener.directory(source.getName()), source, remote);
                    setAttributes(source, remote);
                } else {
                    String adjusted = engine.getPathHelper().adjustForParent(remote, source.getName());
                    makeDirIfNotExists(adjusted);
                    uploadDir(listener.directory(source.getName()), source, adjusted);
                    setAttributes(source, adjusted);
                }
            } else if (source.isFile()) {
                uploadFile(listener.file(source.getName(), source.getLength()), source, remote);
                setAttributes(source, remote);
            } else {
                throw new IOException(source + " is not a file or directory");
            }
        }

        private void upload(final TransferListener listener,
                            final LocalSourceFile local,
                            final String remote)
                throws IOException {
            final String adjustedPath;
            if (local.isDirectory()) {
                adjustedPath = uploadDir(listener.directory(local.getName()), local, remote);
            } else if (local.isFile()) {
                adjustedPath = uploadFile(listener.file(local.getName(), local.getLength()), local, remote);
            } else {
                throw new IOException(local + " is not a file or directory");
            }
            setAttributes(local, adjustedPath);
        }

        private void setAttributes(LocalSourceFile local, String remotePath) throws IOException {
            if (getPreserveAttributes()) {
                engine.setAttributes(remotePath, getAttributes(local));
            }
        }

        private String uploadDir(final TransferListener listener,
                                 final LocalSourceFile local,
                                 final String remote)
                throws IOException {
            makeDirIfNotExists(remote);
            for (LocalSourceFile f : local.getChildren(getUploadFilter()))
                upload(listener, f, engine.getPathHelper().adjustForParent(remote, f.getName()));
            return remote;
        }

        private String uploadFile(final StreamCopier.Listener listener,
                                  final LocalSourceFile local,
                                  final String remote)
                throws IOException {
            final String adjusted = prepareFile(local, remote);
            try (RemoteFile rf = engine.open(adjusted, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
                try (InputStream fis = local.getInputStream();
                     RemoteFile.RemoteFileOutputStream rfos = rf.new RemoteFileOutputStream(0, 16)) {
                    new StreamCopier(fis, rfos)
                            .bufSize(engine.getSubsystem().getRemoteMaxPacketSize() - rf.getOutgoingPacketOverhead())
                            .keepFlushing(false)
                            .listener(listener)
                            .copy();
                }
            }
            return adjusted;
        }

        private boolean makeDirIfNotExists(final String remote) throws IOException {
            try {
                FileAttributes attrs = engine.stat(remote);
                if (attrs.getMode().getType() != FileMode.Type.DIRECTORY) {
                    throw new IOException(remote + " exists and should be a directory, but was a " + attrs.getMode().getType());
                }
                // Was not created, but existed.
                return false;
            } catch (SFTPException e) {
                if (e.getStatusCode() == StatusCode.NO_SUCH_FILE) {
                    log.debug("probeDir: {} does not exist, creating", remote);
                    engine.makeDir(remote);
                    return true;
                } else
                    throw e;
            }

        }

        private String prepareFile(final LocalSourceFile local, final String remote)
                throws IOException {
            final FileAttributes attrs;
            try {
                attrs = engine.stat(remote);
            } catch (SFTPException e) {
                if (e.getStatusCode() == StatusCode.NO_SUCH_FILE) {
                    log.debug("probeFile: {} does not exist", remote);
                    return remote;
                } else
                    throw e;
            }
            if (attrs.getMode().getType() == FileMode.Type.DIRECTORY) {
                throw new IllegalStateException();
//                log.debug("probeFile: {} was directory, path adjusted for {}", remote, local.getName());
//                return engine.getPathHelper().adjustForParent(remote, local.getName());
            } else {
                log.debug("probeFile: {} is a {} file that will be replaced", remote, attrs.getMode().getType());
                return remote;
            }
        }

        private FileAttributes getAttributes(LocalSourceFile local)
                throws IOException {
            final FileAttributes.Builder builder = new FileAttributes.Builder().withPermissions(local.getPermissions());
            if (local.providesAtimeMtime())
                builder.withAtimeMtime(local.getLastAccessTime(), local.getLastModifiedTime());
            return builder.build();
        }

    }
//
//    private static class RemotePath {
//        private String base;
//        private String pathSep;
//        private String relativePath;
//
//        private RemotePath(String base, String pathSep) {
//            this.base = base;
//            this.pathSep = pathSep;
//        }
//
//        public RemotePath(String base, String pathSep, String relativePath) {
//            this.base = base;
//            this.pathSep = pathSep;
//            this.relativePath = relativePath;
//        }
//
//        public String getRelativePath() {
//            return relativePath;
//        }
//
//        private RemotePath subdir(String dir) {
//            return new RemotePath(base, pathSep, relativePath + pathSep + dir);
//        }
//
//        private String filePath(String fileName) {
//            return path() + pathSep + fileName;
//        }
//
//        private String path() {
//            return base + relativePath;
//        }
//    }
}
