/*
 * Copyright 2020 Paul Schaub.
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
package org.pgpainless.sop.commands;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.util.io.Streams;
import org.pgpainless.PGPainless;
import org.pgpainless.encryption_signing.EncryptionBuilderInterface;
import org.pgpainless.encryption_signing.EncryptionStream;
import org.pgpainless.key.protection.UnprotectedKeysProtector;
import org.pgpainless.sop.Print;
import picocli.CommandLine;

import static org.pgpainless.sop.Print.err_ln;
import static org.pgpainless.sop.Print.print_ln;

@CommandLine.Command(name = "sign")
public class Sign implements Runnable {

    public enum Type {
        binary,
        text
    }

    @CommandLine.Option(names = "--no-armor",
            description = "ASCII armor the output",
            negatable = true)
    boolean armor = true;

    @CommandLine.Option(names = "--as", description = "Defaults to 'binary'. If '--as=text' and the input data is not valid UTF-8, sign fails with return code 53.",
    paramLabel = "{binary|text}")
    Type type;

    @CommandLine.Parameters
    File secretKeyFile;

    @Override
    public void run() {
        PGPSecretKeyRing secretKeys;
        try {
            secretKeys = PGPainless.readKeyRing().secretKeyRing(new FileInputStream(secretKeyFile));
        } catch (IOException | PGPException e) {
            err_ln("Error reading secret key ring.");
            err_ln(e.getMessage());
            System.exit(1);
            return;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            EncryptionBuilderInterface.DocumentType documentType = PGPainless.encryptAndOrSign()
                    .onOutputStream(out)
                    .doNotEncrypt()
                    .createDetachedSignature()
                    .signWith(new UnprotectedKeysProtector(), secretKeys);

            EncryptionBuilderInterface.Armor builder = type == Type.text ? documentType.signCanonicalText() : documentType.signBinaryDocument();
            EncryptionStream encryptionStream = armor ? builder.asciiArmor() : builder.noArmor();

            Streams.pipeAll(System.in, encryptionStream);
            encryptionStream.close();

            PGPSignature signature = encryptionStream.getResult().getSignatures().iterator().next();

            print_ln(Print.toString(signature.getEncoded(), armor));
        } catch (PGPException | IOException e) {
            err_ln("Error signing data.");
            err_ln(e.getMessage());
            System.exit(1);
        }
    }
}
