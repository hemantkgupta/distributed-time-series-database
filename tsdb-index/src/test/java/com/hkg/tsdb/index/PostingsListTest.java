package com.hkg.tsdb.index;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostingsListTest {

    @Test
    void appendInOrder_works() {
        PostingsList p = new PostingsList();
        p.append(1L);
        p.append(5L);
        p.append(10L);
        assertThat(p.size()).isEqualTo(3);
        assertThat(p.toArray()).containsExactly(1L, 5L, 10L);
    }

    @Test
    void nonIncreasing_rejected() {
        PostingsList p = new PostingsList();
        p.append(5L);
        assertThatThrownBy(() -> p.append(5L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.append(4L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void intersect_simple() {
        PostingsList a = new PostingsList();
        a.append(1L); a.append(3L); a.append(5L); a.append(7L);
        PostingsList b = new PostingsList();
        b.append(2L); b.append(3L); b.append(5L); b.append(9L);

        PostingsList c = PostingsList.intersect(a, b);
        assertThat(c.toArray()).containsExactly(3L, 5L);
    }

    @Test
    void intersect_disjoint_isEmpty() {
        PostingsList a = new PostingsList();
        a.append(1L); a.append(2L); a.append(3L);
        PostingsList b = new PostingsList();
        b.append(10L); b.append(20L);
        assertThat(PostingsList.intersect(a, b).size()).isEqualTo(0);
    }

    @Test
    void intersect_oneEmpty_isEmpty() {
        PostingsList a = new PostingsList();
        a.append(1L); a.append(2L);
        PostingsList b = new PostingsList();
        assertThat(PostingsList.intersect(a, b).size()).isEqualTo(0);
        assertThat(PostingsList.intersect(b, a).size()).isEqualTo(0);
    }
}
