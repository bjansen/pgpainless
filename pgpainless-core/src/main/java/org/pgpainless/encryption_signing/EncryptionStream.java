// SPDX-FileCopyrightText: 2018 Paul Schaub <vanitasvitae@fsfe.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.pgpainless.encryption_signing;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.operator.PGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.PGPKeyEncryptionMethodGenerator;
import org.pgpainless.algorithm.CompressionAlgorithm;
import org.pgpainless.algorithm.HashAlgorithm;
import org.pgpainless.algorithm.StreamEncoding;
import org.pgpainless.algorithm.SymmetricKeyAlgorithm;
import org.pgpainless.implementation.ImplementationFactory;
import org.pgpainless.key.SubkeyIdentifier;
import org.pgpainless.util.ArmorUtils;
import org.pgpainless.util.ArmoredOutputStreamFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OutputStream that produces an OpenPGP message. The message can be encrypted, signed, or both,
 * depending on its configuration.
 *
 * This class is based upon Jens Neuhalfen's Bouncy-GPG PGPEncryptingStream.
 * @see <a href="https://github.com/neuhalje/bouncy-gpg/blob/master/src/main/java/name/neuhalfen/projects/crypto/bouncycastle/openpgp/encrypting/PGPEncryptingStream.java">Source</a>
 */
public final class EncryptionStream extends OutputStream {

    private static final Logger LOGGER = LoggerFactory.getLogger(EncryptionStream.class);

    private final ProducerOptions options;
    private final EncryptionResult.Builder resultBuilder = EncryptionResult.builder();

    private boolean closed = false;
    // 1 << 8 causes wrong partial body length encoding
    //  1 << 9 fixes this.
    //  see https://github.com/pgpainless/pgpainless/issues/160
    private static final int BUFFER_SIZE = 1 << 9;

    OutputStream outermostStream;
    OutputStream signatureLayerStream;

    private ArmoredOutputStream armorOutputStream = null;
    private OutputStream publicKeyEncryptedStream = null;
    private PGPCompressedDataGenerator compressedDataGenerator;
    private BCPGOutputStream basicCompressionStream;
    private PGPLiteralDataGenerator literalDataGenerator;
    private OutputStream literalDataStream;

    EncryptionStream(@Nonnull OutputStream targetOutputStream,
                     @Nonnull ProducerOptions options)
            throws IOException, PGPException {
        this.options = options;
        outermostStream = targetOutputStream;

        prepareArmor();
        prepareEncryption();
        prepareCompression();
        prepareOnePassSignatures();
        prepareLiteralDataProcessing();
        prepareSigningStream();
        prepareInputEncoding();
    }

    private void prepareArmor() {
        if (!options.isAsciiArmor()) {
            LOGGER.debug("Output will be unarmored");
            return;
        }

        // ArmoredOutputStream better be buffered
        outermostStream = new BufferedOutputStream(outermostStream);

        LOGGER.debug("Wrap encryption output in ASCII armor");
        armorOutputStream = ArmoredOutputStreamFactory.get(outermostStream, options);
        if (options.hasComment()) {
            String[] commentLines = options.getComment().split("\n");
            for (String commentLine : commentLines) {
                if (!commentLine.trim().isEmpty()) {
                    ArmorUtils.addCommentHeader(armorOutputStream, commentLine.trim());
                }
            }
        }
        outermostStream = armorOutputStream;
    }

    private void prepareEncryption() throws IOException, PGPException {
        EncryptionOptions encryptionOptions = options.getEncryptionOptions();
        if (encryptionOptions == null || encryptionOptions.getEncryptionMethods().isEmpty()) {
            // No encryption options/methods -> no encryption
            resultBuilder.setEncryptionAlgorithm(SymmetricKeyAlgorithm.NULL);
            return;
        }

        SymmetricKeyAlgorithm encryptionAlgorithm = EncryptionBuilder.negotiateSymmetricEncryptionAlgorithm(encryptionOptions);
        resultBuilder.setEncryptionAlgorithm(encryptionAlgorithm);
        LOGGER.debug("Encrypt message using {}", encryptionAlgorithm);
        PGPDataEncryptorBuilder dataEncryptorBuilder =
                ImplementationFactory.getInstance().getPGPDataEncryptorBuilder(encryptionAlgorithm);
        dataEncryptorBuilder.setWithIntegrityPacket(true);

        PGPEncryptedDataGenerator encryptedDataGenerator =
                new PGPEncryptedDataGenerator(dataEncryptorBuilder);
        for (PGPKeyEncryptionMethodGenerator encryptionMethod : encryptionOptions.getEncryptionMethods()) {
            encryptedDataGenerator.addMethod(encryptionMethod);
        }

        for (SubkeyIdentifier recipientSubkeyIdentifier : encryptionOptions.getEncryptionKeyIdentifiers()) {
            resultBuilder.addRecipient(recipientSubkeyIdentifier);
        }

        publicKeyEncryptedStream = encryptedDataGenerator.open(outermostStream, new byte[BUFFER_SIZE]);
        outermostStream = publicKeyEncryptedStream;
    }

    private void prepareCompression() throws IOException {
        CompressionAlgorithm compressionAlgorithm = EncryptionBuilder.negotiateCompressionAlgorithm(options);
        resultBuilder.setCompressionAlgorithm(compressionAlgorithm);
        compressedDataGenerator = new PGPCompressedDataGenerator(
                compressionAlgorithm.getAlgorithmId());
        if (compressionAlgorithm == CompressionAlgorithm.UNCOMPRESSED) {
            return;
        }

        LOGGER.debug("Compress using {}", compressionAlgorithm);
        basicCompressionStream = new BCPGOutputStream(compressedDataGenerator.open(outermostStream));
        outermostStream = basicCompressionStream;
    }

    private void prepareOnePassSignatures() throws IOException, PGPException {
        signatureLayerStream = outermostStream;
        SigningOptions signingOptions = options.getSigningOptions();
        if (signingOptions == null || signingOptions.getSigningMethods().isEmpty()) {
            // No singing options/methods -> no signing
            return;
        }

        int sigIndex = 0;
        for (SubkeyIdentifier identifier : signingOptions.getSigningMethods().keySet()) {
            sigIndex++;
            SigningOptions.SigningMethod signingMethod = signingOptions.getSigningMethods().get(identifier);

            if (!signingMethod.isDetached()) {
                PGPSignatureGenerator signatureGenerator = signingMethod.getSignatureGenerator();
                // The last sig is not nested, all others are
                boolean nested = sigIndex != signingOptions.getSigningMethods().size();
                signatureGenerator.generateOnePassVersion(nested).encode(outermostStream);
            }
        }
    }

    private void prepareLiteralDataProcessing() throws IOException {
        if (options.isCleartextSigned()) {
            int[] algorithmIds = collectHashAlgorithmsForCleartextSigning();
            armorOutputStream.beginClearText(algorithmIds);
            return;
        }

        literalDataGenerator = new PGPLiteralDataGenerator();
        literalDataStream = literalDataGenerator.open(outermostStream, options.getEncoding().getCode(),
                options.getFileName(), options.getModificationDate(), new byte[BUFFER_SIZE]);
        outermostStream = literalDataStream;

        resultBuilder.setFileName(options.getFileName())
                .setModificationDate(options.getModificationDate())
                .setFileEncoding(options.getEncoding());
    }

    public void prepareSigningStream() {
        outermostStream = new SignatureGenerationStream(outermostStream, options.getSigningOptions());
    }

    public void prepareInputEncoding() {
        // By buffering here, we drastically improve performance
        // Reason is that CRLFGeneratorStream only implements write(int), so we need BufferedOutputStream to
        // "convert" to write(buf) calls again
        BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outermostStream);
        CRLFGeneratorStream crlfGeneratorStream = new CRLFGeneratorStream(bufferedOutputStream,
                options.isApplyCRLFEncoding() ? StreamEncoding.UTF8 : StreamEncoding.BINARY);
        outermostStream = crlfGeneratorStream;
    }

    private int[] collectHashAlgorithmsForCleartextSigning() {
        SigningOptions signOpts = options.getSigningOptions();
        Set<HashAlgorithm> hashAlgorithms = new HashSet<>();
        if (signOpts != null) {
            for (SigningOptions.SigningMethod method : signOpts.getSigningMethods().values()) {
                hashAlgorithms.add(method.getHashAlgorithm());
            }
        }

        int[] algorithmIds = new int[hashAlgorithms.size()];
        Iterator<HashAlgorithm> iterator = hashAlgorithms.iterator();
        for (int i = 0; i < algorithmIds.length; i++) {
            algorithmIds[i] = iterator.next().getAlgorithmId();
        }

        return algorithmIds;
    }

    @Override
    public void write(int data) throws IOException {
        outermostStream.write(data);
    }

    @Override
    public void write(@Nonnull byte[] buffer) throws IOException {
        write(buffer, 0, buffer.length);
    }


    @Override
    public void write(@Nonnull byte[] buffer, int off, int len) throws IOException {
        outermostStream.write(buffer, 0, len);
    }

    @Override
    public void flush() throws IOException {
        outermostStream.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        outermostStream.close();

        // Literal Data
        if (literalDataStream != null) {
            literalDataStream.flush();
            literalDataStream.close();
        }
        if (literalDataGenerator != null) {
            literalDataGenerator.close();
        }

        if (options.isCleartextSigned()) {
            // Add linebreak between body and signatures
            // TODO: We should only add this line if required.
            //  I.e. if the message already ends with \n, don't add another linebreak.
            armorOutputStream.write('\r');
            armorOutputStream.write('\n');
            armorOutputStream.endClearText();
        }

        try {
            writeSignatures();
        } catch (PGPException e) {
            throw new IOException("Exception while writing signatures.", e);
        }

        // Compressed Data
        compressedDataGenerator.close();

        // Public Key Encryption
        if (publicKeyEncryptedStream != null) {
            publicKeyEncryptedStream.flush();
            publicKeyEncryptedStream.close();
        }

        // Armor
        if (armorOutputStream != null) {
            armorOutputStream.flush();
            armorOutputStream.close();
        }
        closed = true;
    }

    private void writeSignatures() throws PGPException, IOException {
        SigningOptions signingOptions = options.getSigningOptions();
        if (signingOptions == null || signingOptions.getSigningMethods().isEmpty()) {
            return;
        }

        // One-Pass-Signatures are bracketed. That means we have to append the signatures in reverse order
        //  compared to the one-pass-signature packets.
        List<SubkeyIdentifier> signingKeys = new ArrayList<>(signingOptions.getSigningMethods().keySet());
        for (int i = signingKeys.size() - 1; i >= 0; i--) {
            SubkeyIdentifier signingKey = signingKeys.get(i);
            SigningOptions.SigningMethod signingMethod = signingOptions.getSigningMethods().get(signingKey);
            PGPSignatureGenerator signatureGenerator = signingMethod.getSignatureGenerator();
            PGPSignature signature = signatureGenerator.generate();
            if (signingMethod.isDetached()) {
                resultBuilder.addDetachedSignature(signingKey, signature);
            }
            if (!signingMethod.isDetached() || options.isCleartextSigned()) {
                signature.encode(signatureLayerStream);
            }
        }
    }

    public EncryptionResult getResult() {
        if (!closed) {
            throw new IllegalStateException("EncryptionStream must be closed before accessing the Result.");
        }
        return resultBuilder.build();
    }

    public boolean isClosed() {
        return closed;
    }
}
