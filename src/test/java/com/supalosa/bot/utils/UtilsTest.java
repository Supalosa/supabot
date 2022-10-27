package com.supalosa.bot.utils;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class UtilsTest {

    @Test
    void getRetreatPosition() {
        // In a straight line where the enemy is to the right, make sure we move towards the left.
        Point2d p1 = Point2d.of(10f, 10f);
        Point2d p2 = Point2d.of(20f, 10f);
        assertThat(Utils.getRetreatPosition(p1, p2, 5f)).isEqualTo(Point2d.of(5f, 10f));
    }

    @Test
    void getBiasedRetreatPosition() {
        // In a straight line where the enemy is to the right and the bias is to the left, make sure we move towards the left.
        Point2d myPosition = Point2d.of(10f, 10f);
        Point2d enemyPosition = Point2d.of(20f, 10f);
        Point2d biasPosition = Point2d.of(5f, 10f);

        assertThat(Utils.getBiasedRetreatPosition(myPosition, enemyPosition, biasPosition, 5f))
                .isEqualTo(Point2d.of(5f, 10f));
    }

    @Test
    void getBiasedRetreatPosition2() {
        // In a straight line where the enemy is to the right and the bias is to the top, make sure we move towards the top left.
        Point2d myPosition = Point2d.of(10f, 10f);
        Point2d enemyPosition = Point2d.of(20f, 10f);
        Point2d biasPosition = Point2d.of(10f, 5f);

        assertThat(Utils.getBiasedRetreatPosition(myPosition, enemyPosition, biasPosition, 5f))
                .isEqualTo(Point2d.of(6.464466f, 6.464466f));
    }
}