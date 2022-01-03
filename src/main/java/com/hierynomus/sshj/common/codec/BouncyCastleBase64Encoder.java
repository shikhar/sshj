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
package com.hierynomus.sshj.common.codec;

import org.bouncycastle.util.encoders.Base64;

import java.nio.charset.StandardCharsets;

/**
 * Bouncy Castle implementation of Base64 Encoder using org.bouncycastle.util.encoders.Base64
 */
public class BouncyCastleBase64Encoder implements Base64Encoder {

    @Override
    public String encode(final byte[] bytes) {
        final byte[] encoded = Base64.encode(bytes);
        return new String(encoded, StandardCharsets.US_ASCII);
    }
}
