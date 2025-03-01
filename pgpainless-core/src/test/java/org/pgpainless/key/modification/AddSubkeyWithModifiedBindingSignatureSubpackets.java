// SPDX-FileCopyrightText: 2021 Paul Schaub <vanitasvitae@fsfe.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.pgpainless.key.modification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.List;

import org.bouncycastle.bcpg.sig.NotationData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.junit.JUtils;
import org.junit.jupiter.api.Test;
import org.pgpainless.PGPainless;
import org.pgpainless.algorithm.KeyFlag;
import org.pgpainless.key.OpenPgpV4Fingerprint;
import org.pgpainless.key.generation.KeyRingBuilder;
import org.pgpainless.key.generation.KeySpec;
import org.pgpainless.key.generation.type.KeyType;
import org.pgpainless.key.generation.type.eddsa.EdDSACurve;
import org.pgpainless.key.info.KeyRingInfo;
import org.pgpainless.key.protection.SecretKeyRingProtector;
import org.pgpainless.signature.subpackets.SelfSignatureSubpackets;
import org.pgpainless.signature.subpackets.SignatureSubpacketsUtil;

public class AddSubkeyWithModifiedBindingSignatureSubpackets {

    public static final long MILLIS_IN_SEC = 1000;

    @Test
    public void bindEncryptionSubkeyAndModifyBindingSignatureHashedSubpackets() throws PGPException, InvalidAlgorithmParameterException, NoSuchAlgorithmException, IOException {
        SecretKeyRingProtector protector = SecretKeyRingProtector.unprotectedKeys();
        PGPSecretKeyRing secretKeys = PGPainless.generateKeyRing()
                .modernKeyRing("Alice <alice@pgpainless.org>");
        KeyRingInfo before = PGPainless.inspectKeyRing(secretKeys);

        PGPKeyPair secretSubkey = KeyRingBuilder.generateKeyPair(
                KeySpec.getBuilder(KeyType.EDDSA(EdDSACurve._Ed25519), KeyFlag.SIGN_DATA).build());

        long secondsUntilExpiration = 1000;
        secretKeys = PGPainless.modifyKeyRing(secretKeys)
                .addSubKey(secretSubkey, new SelfSignatureSubpackets.Callback() {
                            @Override
                            public void modifyHashedSubpackets(SelfSignatureSubpackets hashedSubpackets) {
                                hashedSubpackets.setKeyExpirationTime(true, secondsUntilExpiration);
                                hashedSubpackets.addNotationData(false, "test@test.test", "test");
                            }
                        }, SecretKeyRingProtector.unprotectedKeys(), protector, KeyFlag.SIGN_DATA)
                .done();

        KeyRingInfo after = PGPainless.inspectKeyRing(secretKeys);
        List<PGPPublicKey> signingKeys = after.getSigningSubkeys();
        signingKeys.removeAll(before.getSigningSubkeys());
        assertFalse(signingKeys.isEmpty());

        PGPPublicKey newKey = signingKeys.get(0);
        Date now = new Date();
        JUtils.assertEquals(
                now.getTime() + MILLIS_IN_SEC * secondsUntilExpiration,
                after.getSubkeyExpirationDate(new OpenPgpV4Fingerprint(newKey)).getTime(), 2 * MILLIS_IN_SEC);
        assertTrue(newKey.getSignatures().hasNext());
        PGPSignature binding = newKey.getSignatures().next();
        List<NotationData> notations = SignatureSubpacketsUtil.getHashedNotationData(binding);
        assertEquals(1, notations.size());
        assertEquals("test@test.test", notations.get(0).getNotationName());
    }
}
