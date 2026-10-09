package com.actset.conversion.engine;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * [E-B] 요소 분리 엔진 — 한 장(RGBA)에 붙어 있는 여러 요소를 잘라 각각 새 이미지로 만든다. AI 없음.
 *
 * <p>poc/q6_split_elements.py의 size 모드(PoC Q6에서 채택)를 Java로 옮긴 것이다.
 * <ol>
 *   <li>alpha ≥ alphaThreshold 픽셀을 전경으로 본다</li>
 *   <li>면적이 큰 덩어리(bigAreaRatio 이상)는 원래 연결만 보고 서로 합치지 않는다</li>
 *   <li>작은 조각(글자 획·가루)은 gap 만큼 팽창해 서로 묶고, 큰 덩어리 바로 옆의 작은 부속(지팡이 별 등)은 그 덩어리에 흡수한다</li>
 *   <li>임계값 아래 옅은 글로우·안티앨리어싱은 가까운 요소에 붙인다</li>
 *   <li>불투명 픽셀이 minAreaRatio 미만인 조각(먼지)은 버린다</li>
 * </ol>
 * 투명 영역으로 떨어져 있지 않고 픽셀이 붙어 있는 요소는 나누지 못한다 — 그건 분해(Qwen) 단계의 몫이다.
 */
public final class ElementSplitter {

    /**
     * 임시값(docs/05 0-3) — PoC 이미지 한 장(544×736)으로 눈대중한 값을 해상도 비율로 바꾼 것이다. 학습 산출물이 아니다.
     *
     * @param mergeGapRatio 긴 변 대비 묶음 간격(544×736에서 6px)
     */
    public record Params(int alphaThreshold, double mergeGapRatio, double minAreaRatio,
                         double bigAreaRatio, double attachRatio) {
        public static final Params TEMP_DEFAULTS = new Params(64, 6.0 / 736, 0.0005, 0.01, 0.15);
    }

    /** 잘라낸 요소 1개. bounds는 입력 이미지 좌표계. */
    public record Element(BufferedImage image, Rectangle bounds, int opaquePixels) {
    }

    private final Params params;

    public ElementSplitter() {
        this(Params.TEMP_DEFAULTS);
    }

    public ElementSplitter(Params params) {
        this.params = params;
    }

    public List<Element> split(BufferedImage source) {
        int w = source.getWidth(), h = source.getHeight(), n = w * h;
        int[] argb = source.getRGB(0, 0, w, h, null, 0, w);
        int[] alpha = new int[n];
        boolean[] fg = new boolean[n];
        for (int i = 0; i < n; i++) {
            alpha[i] = argb[i] >>> 24;
            fg[i] = alpha[i] >= params.alphaThreshold();
        }
        int gap = Math.max(2, (int) Math.round(params.mergeGapRatio() * Math.max(w, h)));

        int[] labels = labelBySize(fg, w, h, gap);
        spreadToHalo(labels, alpha, w, h, gap * 2);

        int maxLabel = Arrays.stream(labels).max().orElse(0);
        int[] minX = new int[maxLabel + 1], minY = new int[maxLabel + 1], maxX = new int[maxLabel + 1], maxY = new int[maxLabel + 1];
        int[] opaque = new int[maxLabel + 1];
        Arrays.fill(minX, Integer.MAX_VALUE);
        Arrays.fill(minY, Integer.MAX_VALUE);
        Arrays.fill(maxX, -1);
        Arrays.fill(maxY, -1);
        for (int i = 0; i < n; i++) {
            int l = labels[i];
            if (l == 0) continue;
            int x = i % w, y = i / w;
            minX[l] = Math.min(minX[l], x);
            minY[l] = Math.min(minY[l], y);
            maxX[l] = Math.max(maxX[l], x);
            maxY[l] = Math.max(maxY[l], y);
            if (fg[i]) opaque[l]++;
        }

        List<Element> out = new ArrayList<>();
        double minOpaque = params.minAreaRatio() * n;
        for (int l = 1; l <= maxLabel; l++) {
            if (maxX[l] < 0 || opaque[l] < minOpaque) continue; // 글로우 말고 실제 불투명 픽셀로 센다 — 가루 제거
            Rectangle r = new Rectangle(minX[l], minY[l], maxX[l] - minX[l] + 1, maxY[l] - minY[l] + 1);
            BufferedImage crop = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < r.height; y++) {
                for (int x = 0; x < r.width; x++) {
                    int i = (r.y + y) * w + (r.x + x);
                    // 요소 라벨(글로우 포함) 안의 원본 픽셀을 그대로 — 경계 안티앨리어싱 보존
                    crop.setRGB(x, y, labels[i] == l ? argb[i] : 0);
                }
            }
            out.add(new Element(crop, r, opaque[l]));
        }
        out.sort(Comparator.comparingInt((Element e) -> e.opaquePixels()).reversed());
        return out;
    }

    /** size 모드: 큰 덩어리는 원래 연결만, 작은 조각만 팽창으로 묶는다. */
    private int[] labelBySize(boolean[] fg, int w, int h, int gap) {
        int n = w * h;
        int[] raw = connectedComponents(fg, w, h);
        int rawCount = Arrays.stream(raw).max().orElse(0);
        int[] sizes = new int[rawCount + 1];
        for (int l : raw) if (l > 0) sizes[l]++;

        int[] out = new int[n];
        int[] bigSize = new int[rawCount + 2];
        int[] rawToBig = new int[rawCount + 1];
        int bigCount = 0;
        for (int l = 1; l <= rawCount; l++) {
            if (sizes[l] >= params.bigAreaRatio() * n) {
                rawToBig[l] = ++bigCount;
                bigSize[bigCount] = sizes[l];
            }
        }
        boolean[] small = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (raw[i] > 0 && rawToBig[raw[i]] > 0) out[i] = rawToBig[raw[i]];
            else small[i] = fg[i];
        }
        // 큰 덩어리 주변 gap(체스판 거리) 이내 픽셀에 그 덩어리 id
        int[] bigReach = nearestLabelWithin(out, w, h, gap);

        int[] clusters = connectedComponents(dilate(small, w, h, gap), w, h);
        int clusterCount = Arrays.stream(clusters).max().orElse(0);
        int[] clusterArea = new int[clusterCount + 1];
        int[][] touchVotes = new int[clusterCount + 1][];
        for (int i = 0; i < n; i++) {
            if (!small[i]) continue;
            int c = clusters[i];
            clusterArea[c]++;
            int b = bigReach[i];
            if (b > 0) {
                if (touchVotes[c] == null) touchVotes[c] = new int[bigCount + 1];
                touchVotes[c][b]++;
            }
        }
        int[] target = new int[clusterCount + 1];
        int nextId = bigCount + 1;
        for (int c = 1; c <= clusterCount; c++) {
            if (clusterArea[c] == 0) continue;
            int t = 0;
            if (touchVotes[c] != null) {
                int b = argMax(touchVotes[c]);
                if (clusterArea[c] < params.attachRatio() * bigSize[b]) t = b; // 큰 덩어리 옆의 작은 부속
            }
            target[c] = t > 0 ? t : nextId++;
        }
        for (int i = 0; i < n; i++) {
            if (small[i]) out[i] = target[clusters[i]];
        }
        return out;
    }

    /** 임계값 아래 옅은 픽셀(alpha>0)을 radius 이내의 가장 가까운 요소에 붙인다. */
    private void spreadToHalo(int[] labels, int[] alpha, int w, int h, int radius) {
        int[] near = nearestLabelWithin(labels, w, h, radius);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == 0 && alpha[i] > 0 && near[i] > 0) labels[i] = near[i];
        }
    }

    /** 다중 출발 BFS(8방향 = 체스판 거리)로 radius 이내 가장 가까운 라벨을 찾는다. 라벨 픽셀 자신은 자기 라벨. */
    static int[] nearestLabelWithin(int[] labels, int w, int h, int radius) {
        int n = w * h;
        int[] result = labels.clone();
        int[] dist = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            if (labels[i] > 0) {
                dist[i] = 0;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int i = queue[head++];
            if (dist[i] >= radius) continue;
            int x = i % w, y = i / w;
            for (int dy = -1; dy <= 1; dy++) {
                int ny = y + dy;
                if (ny < 0 || ny >= h) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= w || (dx == 0 && dy == 0)) continue;
                    int j = ny * w + nx;
                    if (dist[j] == Integer.MAX_VALUE) {
                        dist[j] = dist[i] + 1;
                        result[j] = result[i];
                        queue[tail++] = j;
                    }
                }
            }
        }
        return result;
    }

    /** 4방향 반복 팽창(scipy binary_dilation iterations=r과 같음) = 맨해튼 거리 ≤ r. 2패스 거리변환으로 계산. */
    static boolean[] dilate(boolean[] mask, int w, int h, int r) {
        int n = w * h;
        int[] d = new int[n];
        int inf = w + h + 1;
        for (int i = 0; i < n; i++) d[i] = mask[i] ? 0 : inf;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (x > 0) d[i] = Math.min(d[i], d[i - 1] + 1);
                if (y > 0) d[i] = Math.min(d[i], d[i - w] + 1);
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = y * w + x;
                if (x < w - 1) d[i] = Math.min(d[i], d[i + 1] + 1);
                if (y < h - 1) d[i] = Math.min(d[i], d[i + w] + 1);
            }
        }
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) out[i] = d[i] <= r;
        return out;
    }

    /** 8방향 연결요소 라벨링(외부 공개용). 0은 배경. */
    public static int[] label8(boolean[] mask, int w, int h) {
        return connectedComponents(mask, w, h);
    }

    /** 8방향 연결요소 라벨링. 0은 배경. */
    static int[] connectedComponents(boolean[] mask, int w, int h) {
        int n = w * h;
        int[] labels = new int[n];
        int[] stack = new int[n];
        int next = 0;
        for (int s = 0; s < n; s++) {
            if (!mask[s] || labels[s] != 0) continue;
            next++;
            int top = 0;
            stack[top++] = s;
            labels[s] = next;
            while (top > 0) {
                int i = stack[--top];
                int x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= w) continue;
                        int j = ny * w + nx;
                        if (mask[j] && labels[j] == 0) {
                            labels[j] = next;
                            stack[top++] = j;
                        }
                    }
                }
            }
        }
        return labels;
    }

    private static int argMax(int[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) if (values[i] > values[best]) best = i;
        return best;
    }
}
