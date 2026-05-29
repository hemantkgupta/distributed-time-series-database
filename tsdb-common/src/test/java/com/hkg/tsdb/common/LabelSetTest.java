package com.hkg.tsdb.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabelSetTest {

    @Test
    void canonicalForm_sortsKeysRegardlessOfInsertionOrder() {
        LabelSet a = LabelSet.of("service", "checkout", "region", "us-east");
        LabelSet b = LabelSet.of("region", "us-east", "service", "checkout");
        assertThat(a.canonical()).isEqualTo(b.canonical());
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        assertThat(a).isEqualTo(b);
    }

    @Test
    void fingerprintIsStableAcrossConstructions() {
        LabelSet a = LabelSet.of("service", "checkout");
        LabelSet b = LabelSet.of("service", "checkout");
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    @Test
    void differentLabels_haveDifferentFingerprints() {
        LabelSet a = LabelSet.of("service", "checkout");
        LabelSet b = LabelSet.of("service", "payment");
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    }

    @Test
    void canonicalForm_quotesValuesContainingSpecialChars() {
        LabelSet a = LabelSet.of("url", "/a\"b\\c");
        assertThat(a.canonical()).contains("\\\"");
        assertThat(a.canonical()).contains("\\\\");
    }

    @Test
    void emptyLabels_rejected() {
        assertThatThrownBy(() -> LabelSet.of(Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullValue_rejected() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("k", null);
        assertThatThrownBy(() -> LabelSet.of(m))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyKey_rejected() {
        assertThatThrownBy(() -> LabelSet.of("", "v"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oddNumberOfVarargs_rejected() {
        assertThatThrownBy(() -> LabelSet.of("only-key"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
