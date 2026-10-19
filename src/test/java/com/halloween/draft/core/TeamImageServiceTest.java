package com.halloween.draft.core;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TeamImageServiceTest {

    private static byte[] image(int w, int h, int type, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, type), format, out);
        return out.toByteArray();
    }

    private static byte[] gif(int frames) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames; i++) {
                BufferedImage frame = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
                frame.setRGB(i, i, 0xFFFFFF);
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
        }
        return bytes.toByteArray();
    }

    private static BufferedImage read(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void bigStillIsShrunkKeepingItsShape() throws Exception {
        var p = TeamImageService.process(image(1200, 800, BufferedImage.TYPE_INT_RGB, "png"));
        assertEquals("jpg", p.ext()); // no transparency → smaller as JPG
        assertFalse(p.animated());
        BufferedImage out = read(p.image());
        assertEquals(512, out.getWidth());
        assertEquals(341, out.getHeight());
    }

    @Test
    void transparencyStaysPng() throws Exception {
        var p = TeamImageService.process(image(300, 300, BufferedImage.TYPE_INT_ARGB, "png"));
        assertEquals("png", p.ext());
        assertEquals(300, read(p.image()).getWidth()); // never scaled up
    }

    @Test
    void roleIconIsA128Square() throws Exception {
        var p = TeamImageService.process(image(400, 200, BufferedImage.TYPE_INT_RGB, "jpg"));
        BufferedImage icon = read(p.icon());
        assertEquals(128, icon.getWidth());
        assertEquals(128, icon.getHeight());
    }

    @Test
    void animatedGifIsKeptAsUploaded() throws Exception {
        byte[] raw = gif(4);
        var p = TeamImageService.process(raw);
        assertTrue(p.animated());
        assertEquals("gif", p.ext());
        assertArrayEquals(raw, p.image());
        assertNotNull(p.icon());
    }

    @Test
    void singleFrameGifIsTreatedAsAStill() throws Exception {
        assertFalse(TeamImageService.process(gif(1)).animated());
    }

    @Test
    void refusesWhatIsntAnImage() {
        assertThrows(UserError.class, () -> TeamImageService.process("not an image".getBytes()));
        assertThrows(UserError.class, () -> TeamImageService.process(new byte[0]));
    }

    @Test
    void refusesTinyImagesAndOtherFormats() throws Exception {
        assertThrows(UserError.class, () -> TeamImageService.process(image(8, 8, BufferedImage.TYPE_INT_RGB, "png")));
        assertThrows(UserError.class, () -> TeamImageService.process(image(100, 100, BufferedImage.TYPE_INT_RGB, "bmp")));
    }

    @Test
    void refusesFilesOverTheLimit() {
        assertThrows(UserError.class, () -> TeamImageService.process(new byte[TeamImageService.MAX_UPLOAD_BYTES + 1]));
    }

    @Test
    void fileNames() {
        assertEquals("team-12.png", TeamImageService.fileName(12L, "png"));
        assertEquals("attachment://team-3.gif", TeamImageService.attachmentUrl(3L, "gif"));
        assertNull(TeamImageService.fileName(null, "png"));
    }
}