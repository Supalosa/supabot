package com.supalosa.bot.utils;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class UtilsTest {

    @Test
    void getRetreatPosition() {
        Point2d p1 = Point2d.of(10f, 10f);
        Point2d p2 = Point2d.of(20f, 10f);
        assertThat(Utils.getRetreatPosition(p1, p2, 5f)).isEqualTo(Point2d.of(5f, 10f));
    }
}