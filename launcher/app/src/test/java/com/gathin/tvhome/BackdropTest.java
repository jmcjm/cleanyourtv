package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The backdrop's colour arithmetic: the app tint must stay a dark background. */
public class BackdropTest {

    @Test
    public void mixMovesEachChannel() {
        assertEquals(0xFF000000, Backdrop.mix(0xFFFFFFFF, 0xFF000000, 1f));
        assertEquals(0xFFFFFFFF, Backdrop.mix(0xFFFFFFFF, 0xFF000000, 0f));
        assertEquals(0xFF808080, Backdrop.mix(0xFFFFFFFF, 0xFF000000, 0.5f));
    }

    @Test
    public void aTintIsDarkAndKeepsTheEdges() {
        int[] tint = Backdrop.tinted(0xFFFF0000);
        assertEquals(4, tint.length);
        int centreRed = (tint[0] >> 16) & 0xFF;
        assertTrue("centre " + Integer.toHexString(tint[0]), centreRed > 0 && centreRed <= 0x55);
        assertEquals(0xFF, (tint[0] >>> 24));
        assertEquals(Backdrop.NEUTRAL[2], tint[2]);
        assertEquals(Backdrop.NEUTRAL[3], tint[3]);
    }

    @Test
    public void aTranslucentColourStillGivesAnOpaqueTint() {
        assertEquals(0xFF, Backdrop.tinted(0x40FFFFFF)[0] >>> 24);
    }
}
