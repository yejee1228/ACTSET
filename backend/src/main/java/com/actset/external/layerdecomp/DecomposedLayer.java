package com.actset.external.layerdecomp;

import java.awt.image.BufferedImage;

/** 분해된 RGBA 레이어 1장(docs/02 visual_layers). type: BACKDROP | SUBJECT | DECOR. */
public record DecomposedLayer(String type, BufferedImage image, int x, int y, int width, int height, boolean removable) {
}
