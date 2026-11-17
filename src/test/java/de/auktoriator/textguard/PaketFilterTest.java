package de.auktoriator.textguard;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PaketFilterTest {

    private static boolean vorfilter(String inhalt) {
        return PaketFilter.enthaelt(Unpooled.copiedBuffer(inhalt, StandardCharsets.ISO_8859_1), PaketFilter.SCHILDTEXT);
    }

    @Test
    void findetSchildtextUeberall() {
        assertTrue(vorfilter("front_text"));
        assertTrue(vorfilter("front_text\0\1\2"));
        assertTrue(vorfilter("\0\12xyz front_text\0\3abc"));
        assertTrue(vorfilter("\0\0\0\0\0\0\0\0\12front_text"), "auch am Pufferende");
    }

    @Test
    void findetNichtsOhneSchildtext() {
        assertFalse(vorfilter(""));
        assertFalse(vorfilter("back_text messages"));
        assertFalse(vorfilter("\0\0front_tex"), "abgeschnitten am Ende");
    }

    @Test
    void beachtetLeseposition() {
        var puffer = Unpooled.copiedBuffer("front_text\0\0", StandardCharsets.ISO_8859_1);
        puffer.readerIndex(1);
        assertFalse(PaketFilter.enthaelt(puffer, PaketFilter.SCHILDTEXT), "nur der noch ungelesene Teil zählt");
        assertEquals(1, puffer.readerIndex(), "Puffer bleibt unverändert");
    }
}
