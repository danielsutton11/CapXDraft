package com.halloween.draft.core;

import com.halloween.draft.model.Team;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Team images: checks and shrinks what captains upload, and keeps it in Postgres.
 *
 * Discord's links to uploaded files stop working after about a day, so the bot keeps the image
 * itself and attaches it to the messages that show it (the board, the scoreboard, the panels).
 * Still images are shrunk to at most 512 px; animated GIFs are kept exactly as uploaded so they
 * stay animated, which is why they have a lower size limit. A 128 px square PNG of the first
 * frame is kept too, for role icons.
 */
@Service
public class TeamImageService {
    private static final Logger log = LoggerFactory.getLogger(TeamImageService.class);

    public static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ANIMATED_BYTES = 2 * 1024 * 1024;
    static final int MAX_SIDE = 512;
    static final int ICON_SIDE = 128;
    static final int ICON_MAX_BYTES = 256 * 1024; // Discord's role icon limit
    private static final int MIN_SIDE = 16;
    private static final int MAX_SOURCE_SIDE = 8000; // refuse giant images before decoding them
    private static final int CACHE_MAX_BYTES = 600 * 1024;
    private static final Set<String> FORMATS = Set.of("png", "jpeg", "jpg", "gif");

    /** A checked, shrunk image ready to store. {@code icon} is null if it couldn't be made small enough. */
    public record Processed(byte[] image, String ext, byte[] icon, boolean animated) {}

    private final JdbcTemplate jdbc;
    /** Small images by id, so the board and panels don't hit the database every time. */
    private final Map<Long, byte[]> cache = new ConcurrentHashMap<>();

    public TeamImageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =====================================================================
    // Checking and shrinking
    // =====================================================================

    /** Checks an upload is a usable PNG, JPG or GIF and shrinks it. Throws {@link UserError} if not. */
    public static Processed process(byte[] raw) {
        if (raw == null || raw.length == 0) throw new UserError("That file is empty.");
        if (raw.length > MAX_UPLOAD_BYTES) {
            throw new UserError("That image is " + mb(raw.length) + " — the limit is " + mb(MAX_UPLOAD_BYTES) + ".");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) throw notAnImage();
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!FORMATS.contains(format)) throw notAnImage();
                reader.setInput(in, false, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < MIN_SIDE || h < MIN_SIDE) throw new UserError("That image is too small — use at least " + MIN_SIDE + "×" + MIN_SIDE + " pixels.");
                if (w > MAX_SOURCE_SIDE || h > MAX_SOURCE_SIDE) {
                    throw new UserError("That image is " + w + "×" + h + " pixels — please use one under "
                            + MAX_SOURCE_SIDE + " pixels on each side.");
                }
                boolean animated = format.equals("gif") && reader.getNumImages(true) > 1;
                BufferedImage first = reader.read(0);
                byte[] icon = png(fitSquare(first, ICON_SIDE));
                if (icon.length > ICON_MAX_BYTES) icon = null;

                if (animated) {
                    if (raw.length > MAX_ANIMATED_BYTES) {
                        throw new UserError("Animated GIFs can be up to " + mb(MAX_ANIMATED_BYTES) + " so they stay animated — "
                                + "yours is " + mb(raw.length) + ". Shrink it (for example with ezgif.com) or use a still image.");
                    }
                    return new Processed(raw, "gif", icon, true);
                }
                BufferedImage small = shrink(first, MAX_SIDE);
                return small.getColorModel().hasAlpha()
                        ? new Processed(png(small), "png", icon, false)
                        : new Processed(jpeg(small), "jpg", icon, false);
            } finally {
                reader.dispose();
            }
        } catch (UserError e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.info("Unreadable team image: {}", e.toString());
            throw new UserError("I couldn't read that image. Try saving it again as a PNG or JPG.");
        }
    }

    private static UserError notAnImage() {
        return new UserError("That file isn't a PNG, JPG or GIF image.");
    }

    public static String mb(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return (mb < 10 ? String.format(Locale.ROOT, "%.1f", mb) : String.valueOf(Math.round(mb))) + " MB";
    }

    /** Scales down (never up) so neither side is over {@code max}, keeping the shape. */
    static BufferedImage shrink(BufferedImage src, int max) {
        double scale = Math.min(1.0, (double) max / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage out = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        draw(out, src, 0, 0, w, h);
        return out;
    }

    /** The image scaled to fit a {@code side}×{@code side} transparent square, centred. */
    static BufferedImage fitSquare(BufferedImage src, int side) {
        double scale = (double) side / Math.max(src.getWidth(), src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        draw(out, src, (side - w) / 2, (side - h) / 2, w, h);
        return out;
    }

    private static void draw(BufferedImage out, BufferedImage src, int x, int y, int w, int h) {
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, x, y, w, h, null);
        } finally {
            g.dispose();
        }
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", out)) throw new IOException("No PNG writer");
        return out.toByteArray();
    }

    static byte[] jpeg(BufferedImage img) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    // =====================================================================
    // Storage
    // =====================================================================

    /** Stores the image and returns its id. */
    public long save(String factionKey, Processed p, long uploadedBy) {
        Long id = jdbc.queryForObject(
                "INSERT INTO team_images (faction_key, ext, image, icon, uploaded_by) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, factionKey, p.ext(), p.image(), p.icon(), uploadedBy);
        if (id == null) throw new IllegalStateException("No id returned for the team image");
        if (p.image().length <= CACHE_MAX_BYTES) cache.put(id, p.image());
        return id;
    }

    /** The image's bytes, or null if it's gone. */
    public byte[] image(long id) {
        byte[] cached = cache.get(id);
        if (cached != null) return cached;
        List<byte[]> rows = jdbc.query("SELECT image FROM team_images WHERE id = ?", (rs, i) -> rs.getBytes(1), id);
        if (rows.isEmpty()) return null;
        byte[] bytes = rows.get(0);
        if (bytes.length <= CACHE_MAX_BYTES) cache.put(id, bytes);
        return bytes;
    }

    /** The role-icon version, or null if there isn't one. */
    public byte[] icon(long id) {
        List<byte[]> rows = jdbc.query("SELECT icon FROM team_images WHERE id = ?", (rs, i) -> rs.getBytes(1), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void delete(long id) {
        cache.remove(id);
        jdbc.update("DELETE FROM team_images WHERE id = ?", id);
    }

    /** Clean Up: every image for the faction. Returns how many were deleted. */
    public int deleteAll(String factionKey) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM team_images WHERE faction_key = ?", Long.class, factionKey);
        ids.forEach(cache::remove);
        return jdbc.update("DELETE FROM team_images WHERE faction_key = ?", factionKey);
    }

    // =====================================================================
    // Attaching to messages
    // =====================================================================

    /** "team-12.png" — what messages attach the image as, and what embeds point at. */
    public static String fileName(Team t) {
        return t == null ? null : fileName(t.imageId, t.imageExt);
    }

    public static String fileName(Long imageId, String ext) {
        return imageId == null ? null : "team-" + imageId + "." + (ext == null ? "png" : ext);
    }

    /** "attachment://team-12.png", for an embed's image or thumbnail; null if the team has no image. */
    public static String attachmentUrl(Team t) {
        return t == null ? null : attachmentUrl(t.imageId, t.imageExt);
    }

    public static String attachmentUrl(Long imageId, String ext) {
        String name = fileName(imageId, ext);
        return name == null ? null : "attachment://" + name;
    }

    /** The team's image as a file to attach, or null if it has none (or it's gone). */
    public FileUpload upload(Team t) {
        return t == null ? null : upload(t.imageId, t.imageExt);
    }

    public FileUpload upload(Long imageId, String ext) {
        if (imageId == null) return null;
        byte[] bytes = image(imageId);
        return bytes == null ? null : FileUpload.fromData(bytes, fileName(imageId, ext));
    }
}
