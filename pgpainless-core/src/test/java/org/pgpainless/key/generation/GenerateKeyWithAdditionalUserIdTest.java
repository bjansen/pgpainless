/*
 * Copyright 2018 Paul Schaub.
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
package org.pgpainless.key.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Iterator;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.junit.JUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgpainless.PGPainless;
import org.pgpainless.algorithm.KeyFlag;
import org.pgpainless.implementation.ImplementationFactory;
import org.pgpainless.key.generation.type.KeyType;
import org.pgpainless.key.generation.type.rsa.RsaLength;
import org.pgpainless.key.util.KeyRingUtils;
import org.pgpainless.key.util.UserId;
import org.pgpainless.util.ArmoredOutputStreamFactory;

public class GenerateKeyWithAdditionalUserIdTest {

    @ParameterizedTest
    @MethodSource("org.pgpainless.util.TestUtil#provideImplementationFactories")
    public void test(ImplementationFactory implementationFactory) throws InvalidAlgorithmParameterException, NoSuchAlgorithmException, PGPException, IOException {
        ImplementationFactory.setFactoryImplementation(implementationFactory);
        Date now = new Date();
        Date expiration = new Date(now.getTime() + 1000 * 60);
        PGPSecretKeyRing secretKeys = PGPainless.generateKeyRing()
                .withPrimaryKey(KeySpec.getBuilder(KeyType.RSA(RsaLength._3072))
                        .withKeyFlags(KeyFlag.CERTIFY_OTHER, KeyFlag.SIGN_DATA, KeyFlag.ENCRYPT_COMMS)
                        .withDefaultAlgorithms())
                .withPrimaryUserId(UserId.onlyEmail("primary@user.id"))
                .withAdditionalUserId(UserId.onlyEmail("additional@user.id"))
                .withAdditionalUserId(UserId.onlyEmail("additional2@user.id"))
                .withAdditionalUserId("\ttrimThis@user.id     ")
                .setExpirationDate(expiration)
                .withoutPassphrase()
                .build();
        PGPPublicKeyRing publicKeys = KeyRingUtils.publicKeyRingFrom(secretKeys);

        JUtils.assertEquals(expiration.getTime(), PGPainless.inspectKeyRing(publicKeys).getPrimaryKeyExpirationDate().getTime(),2000);

        Iterator<String> userIds = publicKeys.getPublicKey().getUserIDs();
        assertEquals("primary@user.id", userIds.next());
        assertEquals("additional@user.id", userIds.next());
        assertEquals("additional2@user.id", userIds.next());
        assertEquals("trimThis@user.id", userIds.next());
        assertFalse(userIds.hasNext());

        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ArmoredOutputStream armor = ArmoredOutputStreamFactory.get(byteOut);
        secretKeys.encode(armor);
        armor.close();

        // echo this | gpg --list-packets
        // CHECKSTYLE:OFF
        System.out.println(byteOut.toString("UTF-8"));
        // CHECKSTYLE:ON
    }
}
