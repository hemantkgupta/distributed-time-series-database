package com.hkg.tsdb.compression;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BitIoTest {

    @Test
    void singleBitsRoundtrip() {
        BitWriter w = new BitWriter();
        w.writeBit(1);
        w.writeBit(0);
        w.writeBit(1);
        w.writeBit(1);
        w.writeBit(0);

        BitReader r = new BitReader(w.bytes(), w.bitsWritten());
        assertThat(r.readBit()).isEqualTo(1);
        assertThat(r.readBit()).isEqualTo(0);
        assertThat(r.readBit()).isEqualTo(1);
        assertThat(r.readBit()).isEqualTo(1);
        assertThat(r.readBit()).isEqualTo(0);
    }

    @Test
    void multiBitRoundtrip_varyingWidths() {
        BitWriter w = new BitWriter();
        w.writeBits(0xDEADBEEFL, 32);
        w.writeBits(0x3FFFL, 14);
        w.writeBits(5L, 4);
        w.writeBits(Long.MAX_VALUE, 63);
        w.writeBits(0L, 1);

        BitReader r = new BitReader(w.bytes(), w.bitsWritten());
        assertThat(r.readBits(32)).isEqualTo(0xDEADBEEFL);
        assertThat(r.readBits(14)).isEqualTo(0x3FFFL);
        assertThat(r.readBits(4)).isEqualTo(5L);
        assertThat(r.readBits(63)).isEqualTo(Long.MAX_VALUE);
        assertThat(r.readBit()).isEqualTo(0);
    }

    @Test
    void invalidBitWidth_rejected() {
        BitWriter w = new BitWriter();
        assertThatThrownBy(() -> w.writeBits(0L, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> w.writeBits(0L, 65))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readerExhaustion_throws() {
        BitWriter w = new BitWriter();
        w.writeBits(0xFFL, 8);
        BitReader r = new BitReader(w.bytes(), w.bitsWritten());
        r.readBits(8);
        assertThatThrownBy(r::readBit).isInstanceOf(IllegalStateException.class);
    }
}
