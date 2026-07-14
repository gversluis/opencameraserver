package net.sourceforge.opencamera.webserver;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.material.color.utilities.QuantizerCelebi;
import com.google.android.material.color.utilities.QuantizerResult;
import com.google.android.material.color.utilities.QuantizerWu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

// analyze TGA online: https://schmittl.github.io/tgajs/
public class AmazfitFormat {

    public static final class Options {
        public Options() {}
        @NonNull
        public String toString() {
            return (this.compress?"compress":"uncompressed")+": "+this.width+"x"+this.height+(this.palette == null ? "" : ":"+this.palette.length)+" ("+this.rawData.length+" pixels)";
        }

        public int width; // Image width in pixels
        public int height; // Image height in pixels
        /**
         * Raw pixel data as a flat int array.
         * May be:
         *  - RGBA interleaved  (length == width * height * 4)
         *  - RGB  interleaved  (length == width * height * 3)
         *  - palette indices   (length == width * height)
         */
        public int[] rawData;
        /**
         * Optional colour palette. Each entry is int[] {r, g, b} or {r, g, b, a}.
         * When non-null the palette indices in rawData are used.
         */
        public int[][] palette = null;
        /** Chunk size (bytes) for the write buffer. Default: 1024. */
        public int chunkSize = 1024;
        /** Whether to use RLE compression. Default: false. */
        public boolean compress = true;
        /** Set to bits per pixel to force it, only works for uncompressed images */
        public int forceBitsPerPixel = 0;
    }

    private static final String TAG = "AmazfitFormat";
    private static QuantizerWu quantizer = null;
    private int width;
    private int fakeWidth;
    private int height;
    private int[] rawData;
    private ByteArrayOutputStream outputStream;

    private boolean usePalette;
    private boolean hasAlpha;
    private int bitsPerPixel;
    private int bytesPerPixel;

    private byte[] buffer;
    private int bufferOffset;

    public static ByteArrayOutputStream getBytes(Bitmap bitmap, int quality, boolean compress) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int maxColors = 0xFFFFFF; // 0xFFFFFFFFL;
        Log.d(TAG, "Before colorCount, quality: "+quality);
        // colorCount = Math.toIntExact(Math.round(2.0 * Math.pow(maxColors / 2.0, quality / 100.0)));
        quality = Math.max(0, Math.min(100, quality));

        double curve1 = 1.1;  // 0..0xFF (almost linear)
        double startAt = 2.0;
        int colorCount;
        switch(quality) {
            case 100:
                colorCount = 0xFFFFFF;
                break;
            case 99:
                colorCount = 0xFFFF;
                break;
            default:
                double t1 = Math.min(quality, 100.0-startAt) / (100.0-startAt);
                colorCount = (int)Math.round(startAt + Math.pow(t1, curve1) * (0xFF - startAt));
        }

        Log.d(TAG, "Quality "+quality+" gives "+colorCount+" colors");
        Options options = null;
        if (colorCount<=0xFF) {
            options = getOptionsWithPalette(pixels, colorCount);
            options.compress = compress;    // compress only works with palette atm with Amazfit
        } else {
            options = getOptionsWithBitShifting(pixels, quality);   // much faster but large
        }
        options.width = width;
        options.height = height;
        AmazfitFormat amazfitFormat = new AmazfitFormat();
        return amazfitFormat.encode(options);
    }

    public static Options getOptionsWithBitShifting(int[] integerPixels, int quality) throws IOException {
        // int shift = 7 - (7 * quality) / 100;    // Only makes sense with compression, which Amazfit does not support with uncompressed images?
        int shift = (quality < 100) ? 2 : 0;  // choose 2 because its RGB565 so we need at least 6 bytes...
        int mask = (0xFF << shift) & 0xFF;

        int[] rgbPixels = new int[integerPixels.length*3];
        for(int i=0; i<integerPixels.length; i++) {
            int integerPixel = integerPixels[i];
            rgbPixels[i*3  ] = (integerPixel >> 16) & mask;
            rgbPixels[i*3+1] = (integerPixel >> 8) & mask;
            rgbPixels[i*3+2] = (integerPixel ) & mask;
        }
        Log.d(TAG, "After getOptionsWithBitShifting: " + integerPixels.length + " x 3 = "+rgbPixels.length);
        Options options = new Options();
        options.rawData = rgbPixels;
        options.compress = false; // TODO: support RLE compression, did not find a working version yet
        options.forceBitsPerPixel = quality < 100 ? 16 : 24;
        return options;
    }

    public static Options getOptionsWithPalette(int[] pixels, int maxColors) throws IOException {
        // QuantizerWu is faster than QuantizerCelebi, QuantizerCelebi uses QuantizerWu but gives better quality than QuantizerWu
        //Map<Integer, Integer> colorHistogram = QuantizerCelebi.quantize(pixels, maxColors); // slightly improvement upon Wu but slower
/*
        if (quantizer==null) quantizer = new QuantizerWu();
        QuantizerResult wuResult = quantizer.quantize(pixels, maxColors);
        Map<Integer, Integer> colorHistogram = wuResult.colorToCount;
        int[] palette = new int[colorHistogram.size()];
        int p = 0;
        for (Integer color : colorHistogram.keySet()) palette[p++] = color;
        Log.d(TAG, "After palette: "+palette.length);

        int[] rgbPixels = new int[pixels.length];

        int[] cache = new int[0x1000000];   // rgb, ignore alpha
        Arrays.fill(cache, (byte)-1);
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i] & 0xFFFFFF;
            int nearest = cache[argb];
            if (nearest == -1) {
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = (argb) & 0xFF;
                nearest = findNearestColor(palette, r, g, b);
                cache[argb] = nearest;
            }
            rgbPixels[i] = nearest;
        }
*/
        // FastMedianCutQuantizer is faster than QuantizerWu, QuantizerWu has better quality and smaller files than FastMedianCutQuantizer
        FastMedianCutQuantizer.Result r = FastMedianCutQuantizer.quantize(pixels, maxColors);
        int[] palette = r.palette;
        int[] rgbPixels = r.pixelIndices;
        Log.d(TAG, "After getOptionsWithPalette: " + pixels.length + ", palette: "+palette.length+" (max "+maxColors+")");
        Options options = new Options();
        options.palette = convertIntegerPaletteToRgbArray(palette);
        options.rawData = rgbPixels;
        return options;
    }

    /**
     * Post-processes an OctTreeQuantizer result to guarantee:
     *  - palette.length <= maxColors
     *  - every remaining palette entry is used by at least one pixel
     */
    public static int[] enforceOctTreePaletteLimit(int[] palette, int[] indices, int maxColors) {
        int n = palette.length;
        int[] usage = new int[n];
        for (int idx : indices) usage[idx]++;

        // Merge state: for each original index, which "slot" it currently maps to
        int[] remap = new int[n];
        for (int i = 0; i < n; i++) remap[i] = i;

        // Working color/count per slot (mutable copy)
        List<long[]> slots = new ArrayList<>(); // {r,g,b,count} accumulated
        List<Integer> slotOrigIndex = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (usage[i] == 0) continue; // drop unused up front
            int c = palette[i];
            slots.add(new long[]{ (c>>16)&0xff, (c>>8)&0xff, c&0xff, usage[i] });
            slotOrigIndex.add(i);
        }

        // Merge nearest-color pairs (weighted) until under the limit
        while (slots.size() > maxColors) {
            int bi = -1, bj = -1;
            long best = Long.MAX_VALUE;
            for (int i = 0; i < slots.size(); i++) {
                for (int j = i + 1; j < slots.size(); j++) {
                    long[] a = slots.get(i), b = slots.get(j);
                    long dr = a[0]-b[0], dg = a[1]-b[1], db = a[2]-b[2];
                    long d = dr*dr + dg*dg + db*db; // color distance
                    if (d < best) { best = d; bi = i; bj = j; }
                }
            }
            long[] a = slots.get(bi), b = slots.get(bj);
            long ca = a[3], cb = b[3], total = ca + cb;
            long[] merged = {
                    (a[0]*ca + b[0]*cb) / total,
                    (a[1]*ca + b[1]*cb) / total,
                    (a[2]*ca + b[2]*cb) / total,
                    total
            };
            // j merges into i
            slots.set(bi, merged);
            slots.remove(bj);
            int origJ = slotOrigIndex.remove(bj);
            int origI = slotOrigIndex.get(bi);
            for (int i = 0; i < n; i++) if (remap[i] == origJ) remap[i] = origI;
        }

        // Build final compact palette + final remap table (old index -> new index)
        int[] finalPalette = new int[slots.size()];
        int[] oldToNew = new int[n];
        Arrays.fill(oldToNew, -1);
        for (int s = 0; s < slots.size(); s++) {
            long[] c = slots.get(s);
            finalPalette[s] = 0xFF000000 | ((int)c[0]<<16) | ((int)c[1]<<8) | (int)c[2];
            oldToNew[slotOrigIndex.get(s)] = s;
        }
        for (int i = 0; i < indices.length; i++) {
            int mapped = remap[indices[i]];
            indices[i] = oldToNew[mapped];
        }
        return finalPalette;
    }

    private static int[][] convertIntegerPaletteToRgbArray(int[] palette) {
        int[][] result = new int[palette.length][3];
        for(int i=0; i<palette.length; i++) {
            int r = (palette[i] >> 16) & 0xFF;
            int g = (palette[i] >> 8) & 0xFF;
            int b = (palette[i]) & 0xFF;
            result[i][0] = r;
            result[i][1] = g;
            result[i][2] = b;
        }
        return result;
    }

    private static int findNearestColor(int[] palette, int r, int g, int b) {
        int bestIndex = 0;
        int bestDist = Integer.MAX_VALUE;

        for (int i = 0; i < palette.length; i++) {
            int c = palette[i];

            int pr = (c >> 16) & 0xFF;
            int pg = (c >> 8) & 0xFF;
            int pb = (c) & 0xFF;

            int dr = r - pr;
            int dg = g - pg;
            int db = b - pb;

            int dist = dr * dr + dg * dg + db * db;

            if (dist < bestDist) {
                bestDist = dist;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /* Rather fast, working, and visually optimized but uncompressed
    public static ByteArrayOutputStream getBytes(Bitmap bitmap, int quality) throws IOException {

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int colorCount = Math.max(2, Math.min(256, quality));
        Map<Integer, Integer> colorHistogram = QuantizerCelebi.quantize(pixels, colorCount);

        int[] palette = new int[colorHistogram.size()];
        int p = 0;
        for (Integer color : colorHistogram.keySet()) palette[p++] = color;

        int[] rgbPixels = new int[pixels.length * 3];

        for (int i = 0; i < pixels.length; i++) {

            int argb = pixels[i];

            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = (argb) & 0xFF;

            int nearest = findNearestColor(palette, r, g, b);

            rgbPixels[i * 3]     = (nearest >> 16) & 0xFF;
            rgbPixels[i * 3 + 1] = (nearest >> 8) & 0xFF;
            rgbPixels[i * 3 + 2] = (nearest) & 0xFF;
        }

        // -----------------------------------------
        // 4. Encode to Amazfit format
        // -----------------------------------------
        Options options = new Options();
        options.width = width;
        options.height = height;
        options.rawData = rgbPixels;

        ByteArrayOutputStream fos = new ByteArrayOutputStream();
        AmazfitFormat.encode(options, fos);

        return fos;
    }

    private static int findNearestColor(int[] palette, int r, int g, int b) {

        int bestColor = palette[0];
        int bestDist = Integer.MAX_VALUE;

        for (int c : palette) {

            int pr = (c >> 16) & 0xFF;
            int pg = (c >> 8) & 0xFF;
            int pb = (c) & 0xFF;

            int dr = r - pr;
            int dg = g - pg;
            int db = b - pb;

            int dist = dr * dr + dg * dg + db * db;

            if (dist < bestDist) {
                bestDist = dist;
                bestColor = c;
            }
        }

        return bestColor;
    }
*/

    /* Working but I want higher visual quality
    public static ByteArrayOutputStream getBytes(Bitmap bitmap, int quality) throws IOException {
        // TODO: save Bitmap as amazfit TGA
        int shift = 7 - (7 * quality) / 100;
        int mask = (0xFF << shift) & 0xFF;
        int round = (shift > 0) ? (1 << (shift - 1)) : 0;

        int[] integerPixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(integerPixels,0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        int[] rgbPixels = new int[integerPixels.length*3];
        for(int i=0; i<integerPixels.length; i++) {
            int integerPixel = integerPixels[i];
            rgbPixels[i*3  ] = (integerPixel >> 16) & mask;
            rgbPixels[i*3+1] = (integerPixel >> 8) & mask;
            rgbPixels[i*3+2] = (integerPixel ) & mask;
        }
        Options options = new Options();
        options.width = bitmap.getWidth();
        options.height = bitmap.getHeight();
        options.rawData = rgbPixels;
        // if (true) throw new IOException("Amazfit options: "+ options.toString());
        ByteArrayOutputStream fos = new ByteArrayOutputStream();
        AmazfitFormat.encode(options, fos);
        return fos;
    }
     */

    /**
     * Encode the image described by {@code options} and write the TGA bytes
     * to {@link Options#outputStream}.
     *
     * @return {@code true} on success.
     * @throws IllegalArgumentException for invalid options.
     */
    public ByteArrayOutputStream encode(Options options) throws IOException {
        Log.d(TAG, "TGA encode...");
        outputStream    = new ByteArrayOutputStream();

        // ---- unpack options ------------------------------------------------
        width           = options.width;
        height          = options.height;
        rawData         = options.rawData;
        int[][] palette = options.palette;
        int chunkSize   = options.chunkSize;
        boolean compress = options.compress;

        // ---- validate ------------------------------------------------------
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid width or height " + width + "x" + height);
        }
        if (rawData.length != width * height
                && rawData.length != width * height * 3
                && rawData.length != width * height * 4) {
            throw new IllegalArgumentException(
                    "#pixels " + rawData.length + " do not equal " + width + "x" + height);
        }
        if (width == 1) {
            compress = false; // single pixel is smaller uncompressed
        }
        fakeWidth = width % 16 > 0 ? width + (16 - width % 16) : width; // round up for padding because amazfit requires a row to be dividable by 16

        // TODO: Do not use palette with less than 256 pixels
        usePalette = palette != null && palette.length > 0;
        hasAlpha   = false;

        if (usePalette) {
            hasAlpha = true;    // NOTE Amazfit palette always has alpha
            /*
            for (int[] col : palette) {
                if (col.length >= 4 && col[3] < 255) {
                    hasAlpha = true;
                    break;
                }
            }
             */
        } else if (rawData.length / (width * height) == 4) {
            for (int i = 3; i < rawData.length; i += 4) {
                if (rawData[i] < 255) {
                    hasAlpha = true;
                    break;
                }
            }
        }

        // TODO: allow 16 bitsPerPixel without palette (getOptionsWithBitShifting) which saves almost 30%
        // bitsPerPixel: palette ≤256 → 8 bit, palette >256 → 16 bit; true-colour → 24/32
        if (options.forceBitsPerPixel>0) bitsPerPixel = options.forceBitsPerPixel;
        else
            bitsPerPixel = usePalette
                    ? (palette.length > 256 ? 16 : 8)   // WARN: 16 bit palette entries are not support by Amazfit
                    : (hasAlpha ? 32 : 16); // 16m colors without alpha = 24 bit


        int bitsPerPaletteColor = usePalette ? (hasAlpha ? 32 : 24) : 0;
        bytesPerPixel           = rawData.length / (width * height);

        Log.d(TAG, "palette " + usePalette
                + ", alpha: " + hasAlpha
                + ", compress: " + compress
                + ", palettebits: " + bitsPerPaletteColor
                + ", pixelbytes-in: " + bytesPerPixel
                + ", pixelbits-out: " + bitsPerPixel
                + ", rawData: " + rawData.length);

        // ---- write buffer --------------------------------------------------
        buffer       = new byte[chunkSize];
        bufferOffset = 0;

        int paletteLength = usePalette ? Math.max(palette.length, 256) : 0;    // amazfit requires minimal palette of 256 colors
        Log.d(TAG, "Palette length: "+paletteLength);

        // ---- TGA header (18 bytes + 9-byte developer area = 65 bytes total) -
        {
            byte[] tgaHeader = new byte[17 + 9 + 38];
            tgaHeader[0x00] = 0x2E;                            // ID length
            tgaHeader[0x01] = (byte) (usePalette ? 1 : 0);    // colour-map type
            tgaHeader[0x02] = (byte) (usePalette                       // image type
                    ? (compress ? 9 : 1)
                    : (compress ? 10 : 2)
            );
            tgaHeader[0x03] = 0;                               // first colour-map entry index (lo)
            tgaHeader[0x04] = 0;                               // first colour-map entry index (hi)
            tgaHeader[0x05] = usePalette ? (byte)(paletteLength & 0xFF)       : 0; // colour-map length lo
            tgaHeader[0x06] = usePalette ? (byte)(paletteLength >> 8 & 0xFF)  : 0; // colour-map length hi
            tgaHeader[0x07] = (byte) bitsPerPaletteColor;      // bits per palette entry
            tgaHeader[0x08] = 0;                               // x-origin lo
            tgaHeader[0x09] = 0;                               // x-origin hi
            tgaHeader[0x0A] = 0;                               // y-origin lo
            tgaHeader[0x0B] = 0;                               // y-origin hi
            tgaHeader[0x0C] = (byte)(fakeWidth  & 0xFF);       // image width lo
            tgaHeader[0x0D] = (byte)(fakeWidth  >> 8 & 0xFF);  // image width hi
            tgaHeader[0x0E] = (byte)(height & 0xFF);           // image height lo
            tgaHeader[0x0F] = (byte)(height >> 8 & 0xFF);      // image height hi
            tgaHeader[0x10] = (byte) bitsPerPixel;             // bits per pixel
            tgaHeader[0x11] = hasAlpha ? (byte)0x28 : (byte)0x20; // image descriptor

            // ZeppOS / Amazfit custom developer area ("SOMH" signature)
            tgaHeader[0x12] = 0x53; // 'S'
            tgaHeader[0x13] = 0x4F; // 'O'
            tgaHeader[0x14] = 0x4D; // 'M'
            tgaHeader[0x15] = 0x48; // 'H'
            tgaHeader[0x16] = (byte)(width & 0xFF);       // "real" width lo (useful for NXP rounding)
            tgaHeader[0x17] = (byte)(width >> 8 & 0xFF);  // "real" width hi
            tgaHeader[0x18] = 0;
            tgaHeader[0x19] = 0;
            tgaHeader[0x1A] = 1;
            // bytes 0x1B(27)..0x40(64) remain zero (already initialised by Java)

            outputStream.write(tgaHeader, 0, tgaHeader.length);
        }

        // ---- optional colour-map -------------------------------------------
        if (usePalette) {
            int bytesPerEntry = hasAlpha ? 4 : 3;
            byte[] paletteData = new byte[paletteLength * bytesPerEntry];   // amazfit requires 256 color palette
            for (int i = 0; i < palette.length; i++) {
                int[] col = palette[i];
                int r = col[0] & 0xFF;
                int g = col[1] & 0xFF;
                int b = col[2] & 0xFF;
                int a = (col.length >= 4 ? col[3] & 0xFF : 0xFF);
                // TGA palette order: B, G, R, (A)
                if (hasAlpha) {
                    paletteData[i * 4]     = (byte) r;
                    paletteData[i * 4 + 1] = (byte) g;
                    paletteData[i * 4 + 2] = (byte) b;
                    paletteData[i * 4 + 3] = (byte) a;
                } else {
                    paletteData[i * 3]     = (byte) r;
                    paletteData[i * 3 + 1] = (byte) g;
                    paletteData[i * 3 + 2] = (byte) b;
                }
            }
            outputStream.write(paletteData, 0, paletteData.length);
        }

        // ---- pixel data ----------------------------------------------------
        Log.d(TAG, "Write TGA pixels...");
        if (compress) {
            writeRLE();
        } else {
            writeUncompressed();
        }
        flushBuffer();

        return outputStream;
    }

    // -----------------------------------------------------------------------
    // Buffered writer
    // -----------------------------------------------------------------------

    /** Flush whatever is in the write buffer to the output stream. */
    private void flushBuffer() {
        if (bufferOffset > 0) {
            outputStream.write(buffer, 0, bufferOffset);
            bufferOffset = 0;
        }
    }

    /**
     * Append {@code data} to the write buffer, flushing as needed.
     * Works with int[] internally (values 0–255) and casts to byte on write.
     */
    private void emit(byte[] data) throws IOException {
        outputStream.write(data);
    }

    // -----------------------------------------------------------------------
    // Pixel helpers
    // -----------------------------------------------------------------------

    /**
     * Return the encoded bytes for pixel (x, y) as an int[] (values 0–255).
     * Palette images: 1 or 2 bytes (index).
     * True-colour:    BGR or BGRA.
     */
    private byte[] getPixel(int x, int y) throws IOException {
        if (x>=width) x = width - 1;    // padding gets the same color as the last pixel of the row for good compression
        int index = y * width + x;
        if (index<0 || index * bytesPerPixel >= rawData.length) {
            Log.w(TAG, "Index out of bounds");
            return new byte[0];
        }

        if (usePalette) {
            if (bitsPerPixel == 8) {
                return new byte[]{ (byte) (rawData[index] & 0xFF) };
            } else {
                return new byte[]{ (byte) (rawData[index] & 0xFF), (byte) ((rawData[index] >> 8) & 0xFF) };
            }
        }

        // TODO: add 16 bit const pixel565 = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
        int base  = index * bytesPerPixel;
        byte r     = (byte) (rawData[base]     & 0xFF);
        byte g     = (byte) (rawData[base + 1] & 0xFF);
        byte b     = (byte) (rawData[base + 2] & 0xFF);

        byte[] result = new byte[bitsPerPixel/8];
        switch (bitsPerPixel) {
            case 16: // Amazfit=RGB565, TGA=ARGB1555 https://www.gamers.org/dEngine/quake3/TGA.txt
                int pixel = ((r & 0xF8) << 8) |
                            ((g & 0xFC) << 3) |
                            ((b & 0xF8) >> 3);
                result[0] = (byte)(pixel & 0xFF);
                result[1] = (byte)(pixel >> 8);
                break;
            case 24:
                result[0] = b;
                result[1] = g;
                result[2] = r;
                break;
            case 32:
                result[0] = b;
                result[1] = g;
                result[2] = r;
                if (base + 3 >= rawData.length) throw new IOException("The image does not have this many bytes, are you sure it is RGBA and not RGB?");
                byte a = (byte) (rawData[base + 3] & 0xFF);
                result[3] = a;
                break;
            default:
                throw new IOException("Only 16 and 24 bit are supported");
        }
        return result;
    }

    /** Return true when two pixel byte arrays are equal. */
    private boolean samePixelColor(byte[] c1, byte[] c2) {
        if (c1 == null || c2 == null || c1.length != c2.length) return false;
        for (byte i = 0; i < c1.length; i++) {
            if (c1[i] != c2[i]) return false;
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // RLE encoding
    // -----------------------------------------------------------------------

    /**
     * Emit a run of {@code count} identical pixels.
     * TGA RLE: header byte = 0x80 | (count − 1), then one pixel value.
     *
     * @return 0  (so callers can reset their counter in one expression)
     */
    private int writeIdentical(int count, byte[] pixel) throws IOException {
        if (count > 0x80) throw new IllegalStateException("RLE length too long");
        // Log.d(TAG, String.format("write 0x%02x identical (data: 0x%02x) %s", count, 0x80 | count-1, pixel.length == 1 ? "palette "+pixel[0] : pixel.length+" pixels"));
        emit(new byte[]{ (byte) (0x80 | (count - 1)) });
        emit(pixel);
        return 0;
    }

    /**
     * Emit a run of {@code count} non-repeating pixels ending at column {@code xEnd}.
     * TGA RLE: header byte = count − 1, then count pixel values.
     *
     * @return 0  (so callers can reset their counter in one expression)
     */
    private int writeDifferent(int count, int xEnd, int y) throws IOException {
        if (count > 0x80) throw new IllegalStateException("RLE length too long");
        // byte[] pixel = getPixel(xEnd - 1, y); Log.d(TAG, String.format("write 0x%02x different (data: 0x%02x) %s", count, count-1, pixel.length == 1 ? "palette "+pixel[0]: pixel.length+" pixels"));
        emit(new byte[]{ (byte) (count-1) });
        for (int x = xEnd - count; x < xEnd; x++) emit(getPixel(x, y));
        return 0;
    }

    private String pixelToString(byte[] pixel) {
        String result = "px";
        for(byte p: pixel) result += " "+p;
        return result;
    }


    private void writeRLE() throws IOException {
        for (int y = 0; y < height; y++) {
            int x = 0;
            while (x < fakeWidth) {
                byte[] pixel = getPixel(x, y);

                // Check if next pixel is the same (run-length / identical run)
                if (x + 1 < fakeWidth && samePixelColor(pixel, getPixel(x + 1, y))) {
                    int count = 1;
                    while (x + count < fakeWidth && count < 0x80 && samePixelColor(pixel, getPixel(x + count, y))) count++;
                    writeIdentical(count, pixel);
                    x += count;
                } else {
                    // Raw / different run
                    int count = 1;
                    while (x + count < fakeWidth && count < 0x80) {
                        byte[] curr = getPixel(x + count, y);
                        // Stop if two consecutive pixels are the same (better to start a new identical run)
                        if (count + 1 < 128 && x + count + 1 < fakeWidth && samePixelColor(curr, getPixel(x + count + 1, y))) break;
                        if (samePixelColor(getPixel(x + count - 1, y), curr)) break;
                        count++;
                    }
                    writeDifferent(count, x + count, y);
                    x += count;
                }
            }
        }
    }

    /** Write all pixel data uncompressed, row by row, left to right. */
    private void writeUncompressed() throws IOException {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < fakeWidth; x++) {
                emit(getPixel(x, y));   // getPixel returns bgr
            }
        }
    }

}
