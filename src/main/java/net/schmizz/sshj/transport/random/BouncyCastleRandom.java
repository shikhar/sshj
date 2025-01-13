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
package net.schmizz.sshj.transport.random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.NoSuchProviderException;
import java.security.SecureRandom;

/**
 * BouncyCastle <code>Random</code>. This pseudo random number generator uses BouncyCastle non fips.
 * The JRE random will be used when creating a new generator to add some random data to the seed.
 */
public class BouncyCastleRandom
        implements Random {

    private static final Logger logger = LoggerFactory.getLogger(BouncyCastleRandom.class);

    /** Named factory for the BouncyCastle <code>Random</code> */
    public static class Factory
            implements net.schmizz.sshj.common.Factory<Random> {

        @Override
        public Random create() {
            return new BouncyCastleRandom();
        }

    }
    private byte[] tmp = new byte[16];
    private final SecureRandom random;

  public BouncyCastleRandom() {
    logger.info("Generating random seed from SecureRandom of BC.");
    long t = System.currentTimeMillis();
    try {
      // Use SecureRandom with the BC provider
      random = SecureRandom.getInstance("DEFAULT", "BC");
    } catch (NoSuchProviderException e) {
      throw new RuntimeException("BC provider is not in the classpath", e);
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize SecureRandom with BC provider", e);
    }
    logger.debug("Creating random seed took {} ms", System.currentTimeMillis() - t);
  }

  @Override
  public synchronized void fill(byte[] bytes, int start, int len) {
    if (start == 0 && len == bytes.length) {
      random.nextBytes(bytes);
    } else {
      synchronized (this) {
        if (len > tmp.length) tmp = new byte[len];
        random.nextBytes(tmp);
        System.arraycopy(tmp, 0, bytes, start, len);
      }
    }
  }

    @Override
    public void fill(byte[] bytes) {
        random.nextBytes(bytes);
    }

}
