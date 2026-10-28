import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Chinese speech through the macOS {@code say} command. */
class SaySpeechTest {

    private static void assumeSay() {
        assumeTrue(new File(SaySpeech.SAY).canExecute(), "no say command on this machine");
    }

    @Test
    void synthesizesChineseToAWavTheSoundSystemCanPlay() throws Exception {
        assumeSay();
        byte[] wav = new SaySpeech().synthesize("你好，世界。", "Tingting");

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat f = ais.getFormat();
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, f.getEncoding());
            assertEquals(1, f.getChannels());
            assertEquals(16, f.getSampleSizeInBits());
            assertEquals(24000f, f.getSampleRate());
            assertTrue(TimeStretch.supports(f), "the speed control must apply to Chinese too");
            assertTrue(ais.getFrameLength() > f.getFrameRate() / 2, "shorter than half a second: " + ais.getFrameLength());
        }
    }

    @Test
    void unknownVoiceFailsWithAHintAboutInstalledVoices() {
        assumeSay();
        IOException e = assertThrows(IOException.class, () -> new SaySpeech().synthesize("你好", "NoSuchVoice"));
        assertTrue(e.getMessage().contains("NoSuchVoice"), e.getMessage());
        assertTrue(e.getMessage().contains("say -v '?'"), e.getMessage());
    }

    @Test
    void blankVoiceFallsBackToTingting() {
        List<String> cmd = SaySpeech.command("  ", Path.of("/tmp/out.wav"));
        int v = cmd.indexOf("-v");
        assertTrue(v >= 0, cmd.toString());
        assertEquals("Tingting", cmd.get(v + 1));
    }

    @Test
    void pipelineReadsEveryChunkThroughTheGivenPlayer() throws Exception {
        assumeSay();
        List<Integer> played = new CopyOnWriteArrayList<>();
        SpeechPipelineTest.Recording listener = new SpeechPipelineTest.Recording();
        SaySpeech say = new SaySpeech();
        SpeechPipeline p = new SpeechPipeline(chunk -> say.synthesize(chunk, "Tingting"), wav -> played.add(wav.length), say::cancel);

        p.start(List.of("你好。", "再见。"), listener);
        listener.awaitEnd();

        assertEquals(List.of("done"), listener.ends, () -> "failure: " + listener.failure);
        assertEquals(2, played.size());
        assertTrue(played.get(0) > 44 && played.get(1) > 44, played.toString());
    }

    // ---------------------------------------------------------------- voices

    private static final String LISTING = String.join("\n",
            "Albert              en_US    # Hello! My name is Albert.",
            "Eddy (Chinese (China mainland)) zh_CN    # 你好！我叫Eddy。",
            "Eddy (Chinese (Taiwan)) zh_TW    # 你好，我叫Eddy。",
            "Eddy (English (US)) en_US    # Hello! My name is Eddy.",
            "Meijia (Chinese (Taiwan)) zh_TW    # 你好，我叫美佳。",
            "Meijia (Chinese (Taiwan)) zh_TW    # 你好，我叫美佳。",
            "Sinji (Chinese (Hong Kong)) zh_HK    # 你好！我叫善怡。",
            "Tingting (Chinese (China mainland)) zh_CN    # 你好！我叫婷婷。",
            "Tingting (Chinese (China mainland)) zh_CN    # 你好！我叫婷婷。",
            "Bad News            en_US    # Hello! My name is Bad News.");

    @Test
    void listsTheChineseVoicesOnceEachWithTingtingFirst() {
        assertEquals(List.of("Tingting", "Eddy", "Meijia", "Sinji"), SaySpeech.chineseVoices(LISTING));
    }

    @Test
    void recognizesTheHyphenatedTingtingOfOlderSystems() {
        String older = "Albert              en_US    # Hello! My name is Albert.\n"
                + "Mei-Jia             zh_TW    # 您好，我叫美佳。\n"
                + "Ting-Ting           zh_CN    # 您好，我叫Ting-Ting。";
        assertEquals(List.of("Ting-Ting", "Mei-Jia"), SaySpeech.chineseVoices(older));
    }

    @Test
    void offersTingtingWhenNoChineseVoiceIsListed() {
        assertEquals(List.of("Tingting"), SaySpeech.chineseVoices("Albert              en_US    # Hello!"));
    }

    @Test
    void installedChineseVoicesStartWithTingting() {
        assumeSay();
        List<String> voices = SaySpeech.installedChineseVoices();
        assertFalse(voices.isEmpty());
        assertEquals("tingting", voices.get(0).replace("-", "").toLowerCase());
    }
}
