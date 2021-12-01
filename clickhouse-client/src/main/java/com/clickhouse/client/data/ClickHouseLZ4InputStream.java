package com.clickhouse.client.data;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import com.clickhouse.client.ClickHouseChecker;

import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

/**
 * Reader from clickhouse in lz4.
 */
public class ClickHouseLZ4InputStream extends InputStream {
    private static final LZ4Factory factory = LZ4Factory.fastestInstance();

    static final int MAGIC = 0x82;

    private final InputStream stream;

    private byte[] currentBlock;
    private int pointer;

    private void readFully(byte b[], int off, int len) throws IOException {
        if (len < 0) {
            throw new IndexOutOfBoundsException();
        }
        int n = 0;
        while (n < len) {
            int count = stream.read(b, off + n, len - n);
            if (count < 0) {
                throw new EOFException();
            }
            n += count;
        }
    }

    private int readUnsignedByte() throws IOException {
        int ch = stream.read();
        if (ch < 0) {
            throw new EOFException();
        }
        return ch;
    }

    public ClickHouseLZ4InputStream(InputStream stream) {
        this.stream = ClickHouseChecker.nonNull(stream, "InputStream");
    }

    @Override
    public int available() throws IOException {
        int estimated = stream.available();
        if (estimated == 0 && currentBlock != null) {
            estimated = currentBlock.length - pointer;
        }
        return estimated;
    }

    @Override
    public int read() throws IOException {
        return checkNext() ? 0xFF & currentBlock[pointer++] : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        if (!checkNext())
            return -1;

        int copied = 0;
        int targetPointer = off;
        while (copied != len) {
            int toCopy = Math.min(currentBlock.length - pointer, len - copied);
            System.arraycopy(currentBlock, pointer, b, targetPointer, toCopy);
            targetPointer += toCopy;
            pointer += toCopy;
            copied += toCopy;
            if (!checkNext()) { // finished
                break;
            }
        }
        return copied;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }

    private boolean checkNext() throws IOException {
        if (currentBlock == null || pointer == currentBlock.length) {
            currentBlock = readNextBlock();
            pointer = 0;
        }
        return currentBlock != null && pointer < currentBlock.length;
    }

    private int readInt() throws IOException {
        byte b1 = (byte) readUnsignedByte();
        byte b2 = (byte) readUnsignedByte();
        byte b3 = (byte) readUnsignedByte();
        byte b4 = (byte) readUnsignedByte();

        return b4 << 24 | (b3 & 0xFF) << 16 | (b2 & 0xFF) << 8 | (b1 & 0xFF);
    }

    // every block is:
    private byte[] readNextBlock() throws IOException {
        int read = stream.read();
        if (read < 0)
            return null;

        byte[] checksum = new byte[16];
        checksum[0] = (byte) read;
        // checksum - 16 bytes.
        readFully(checksum, 1, 15);
        ClickHouseBlockChecksum expected = ClickHouseBlockChecksum.fromBytes(checksum);
        // header:
        // 1 byte - 0x82 (shows this is LZ4)
        int magic = readUnsignedByte();
        if (magic != MAGIC)
            throw new IOException("Magic is not correct: " + magic);
        // 4 bytes - size of the compressed data including 9 bytes of the header
        int compressedSizeWithHeader = readInt();
        // 4 bytes - size of uncompressed data
        int uncompressedSize = readInt();
        int compressedSize = compressedSizeWithHeader - 9; // header
        byte[] block = new byte[compressedSize];
        // compressed data: compressed_size - 9 байт.
        readFully(block, 0, block.length);

        ClickHouseBlockChecksum real = ClickHouseBlockChecksum.calculateForBlock((byte) magic, compressedSizeWithHeader,
                uncompressedSize, block, compressedSize);
        if (!real.equals(expected)) {
            throw new IllegalArgumentException("Checksum doesn't match: corrupted data.");
        }

        byte[] decompressed = new byte[uncompressedSize];
        LZ4FastDecompressor decompressor = factory.fastDecompressor();
        decompressor.decompress(block, 0, decompressed, 0, uncompressedSize);
        return decompressed;
    }
}
