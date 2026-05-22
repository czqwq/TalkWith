package com.czqwq.talkwith.mixin;

import net.minecraft.client.gui.FontRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import cpw.mods.fml.common.Loader;

/**
 * Fallback fix for Unicode/CJK full-width character rendering when Angelica is not installed.
 *
 * <p>
 * Vanilla {@link FontRenderer#getCharWidth(char)} has two bugs when {@code unicodeFlag} is true:
 * <ol>
 * <li>Sign-extension: {@code glyphWidth[c] >>> 4} interprets the byte as a signed {@code int},
 * producing a huge value for bytes &ge; 0x80. This makes the computed advance massively
 * negative, pushing subsequent glyphs off-screen so the entire line appears blank.</li>
 * <li>Zero-glyph floor: chars whose {@code glyphWidth[]} entry is {@code 0} (e.g. many
 * full-width forms in Unicode pages that have no bitmap glyph) return width 0, which
 * causes text-wrapping logic to stall or produce empty lines.</li>
 * <li>{@code Math.min(7, …)} cap: clips the computed width to 7 pixels, which is too narrow
 * for wide CJK glyphs (&asymp;8-9 px).</li>
 * </ol>
 *
 * <p>
 * This mixin is placed in the {@code "client"} section of the mixin config so it is never
 * applied on the server side. It also checks for Angelica at runtime and becomes a no-op when
 * Angelica is loaded, letting Angelica's superior font renderer take over.
 */
@Mixin(value = FontRenderer.class, remap = true)
public abstract class MixinFontRenderer {

    @Shadow
    private boolean unicodeFlag;

    @Shadow
    protected byte[] glyphWidth;

    /** Cached result of the Angelica presence check (null = not yet checked). */
    @Unique
    private static volatile Boolean talkwith$angelicaPresent = null;

    @Unique
    private static boolean talkwith$isAngelicaLoaded() {
        if (talkwith$angelicaPresent == null) {
            talkwith$angelicaPresent = Loader.isModLoaded("angelica");
        }
        return talkwith$angelicaPresent;
    }

    /**
     * Replaces the character-width computation for Unicode glyphs when Angelica is absent.
     *
     * <ul>
     * <li>Uses {@code & 0xFF} to prevent sign-extension when reading the glyph-width byte.</li>
     * <li>Returns a minimum width of 1 for characters whose glyph entry is zero (no bitmap
     * data), instead of the vanilla 0 which stalls the text-wrap loop.</li>
     * <li>Removes the {@code Math.min(7, …)} cap so wide CJK glyphs get their full width.</li>
     * </ul>
     */
    @Inject(method = "getCharWidth", at = @At("HEAD"), cancellable = true, require = 0, allow = 1)
    private void talkwith$fixUnicodeCharWidth(char par1Char, CallbackInfoReturnable<Integer> cir) {
        if (talkwith$isAngelicaLoaded()) {
            return;
        }
        if (!unicodeFlag) {
            return;
        }
        // These two are handled identically by vanilla regardless of unicodeFlag.
        if (par1Char == '\u00a7') { // § – format-code sentinel
            cir.setReturnValue(-1);
            return;
        }
        if (par1Char == ' ') {
            cir.setReturnValue(4);
            return;
        }

        // Fix 1: mask to unsigned to prevent sign-extension (vanilla uses bare >>> 4).
        int glyph = glyphWidth[par1Char] & 0xFF;

        // Fix 2: return a minimal positive width for chars with no glyph bitmap.
        if (glyph == 0) {
            cir.setReturnValue(1);
            return;
        }

        int startCol = glyph >>> 4;
        int endCol = (glyph & 0x0F) + 1;

        // Fix 3: compute full width without the vanilla Math.min(7, …) cap.
        int width = (int) Math.ceil((endCol - startCol) / 2.0F) + 1;
        cir.setReturnValue(Math.max(1, width));
    }
}
