package com.pokeemc.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UiScalingTest {

    @Test
    void keepsFullScaleWhenWindowFits() {
        assertEquals(1.0f, UiScaling.fitScale(1280, 800, 470, 250));
    }

    @Test
    void shrinksToThreeQuartersWhenFullScaleOverflows() {
        // 470 * 1.0 = 470 > 460 - 12 = 448；470 * 0.75 = 352.5 <= 448
        assertEquals(0.75f, UiScaling.fitScale(460, 400, 470, 250));
    }

    @Test
    void shrinksToHalfWhenThreeQuartersStillOverflows() {
        // 470 * 0.75 = 352.5 > 300 - 12；470 * 0.5 = 235 <= 288
        assertEquals(0.5f, UiScaling.fitScale(300, 220, 470, 250));
    }

    @Test
    void neverReturnsBelowHalfPreset() {
        assertEquals(0.5f, UiScaling.fitScale(100, 80, 470, 250));
    }
}
