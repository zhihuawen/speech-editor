import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaSpeechVoicesTest {

    /** Every voice that {@code ollama show phonon} listed on 2026-10-28. */
    private static final List<String> PHONON_VOICES = List.of(
            "Alfie", "Azgorath", "Bryony", "Captain Silas Blackwater", "default", "Delia", "Easton", "Edwin",
            "Elodie-Rose", "Everly", "Fairy Godmother", "Fennick the Young Elf", "Freya", "Grub the Ogre",
            "Hadley", "Harper", "Hugh", "Imogen", "Juniper", "Lottie", "Maeve", "Master Aldric", "Micah",
            "Miles", "Morwenna of the Deep", "Old Mortlake", "Pippa", "Preston", "Russell", "Sir Bramwell",
            "Spencer", "Teddy", "The Snow Queen", "The Thing Under the Stairs", "Toby", "Trevor",
            "Vyrmathrax the Ancient", "Zoey");

    @Test
    void dropdownOffersEveryPhononVoice() {
        Set<String> offered = new LinkedHashSet<>(Arrays.asList(OllamaSpeech.DEFAULT_VOICES));
        List<String> missing = PHONON_VOICES.stream().filter(v -> !offered.contains(v)).toList();
        assertTrue(missing.isEmpty(), () -> "missing voices: " + missing);
    }

    @Test
    void dropdownStartsWithDefaultAndHasNoDuplicates() {
        assertEquals("default", OllamaSpeech.DEFAULT_VOICES[0]);
        assertEquals(OllamaSpeech.DEFAULT_VOICES.length, new LinkedHashSet<>(Arrays.asList(OllamaSpeech.DEFAULT_VOICES)).size());
    }
}
