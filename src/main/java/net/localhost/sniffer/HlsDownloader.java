package net.localhost.sniffer;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** m3u8/mp4 を端末ローカルでDL→mp4化。外部依存なし（標準API+MediaMuxer）。 */
public class HlsDownloader {

    public interface Listener {
        void onProgress(int pct, String msg);
        void onDone(File out);
        void onError(String msg);
    }

    private static final String TAG = "Sniffer";
    private static final boolean DBG_MUX = false; // remux PTSデバッグ（フレーム毎ログ）
    private static final int CONC = 6;

    private final MediaHit hit;
    private final File outDir;
    private final File tmpDir;
    private final Listener cb;
    private volatile boolean canceled = false;

    public HlsDownloader(MediaHit hit, File outDir, File cacheDir, Listener cb) {
        this.hit = hit;
        this.outDir = outDir;
        this.tmpDir = new File(cacheDir, "dl_" + System.currentTimeMillis());
        this.cb = cb;
    }

    public void cancel() { canceled = true; }

    public void run() {
        try {
            outDir.mkdirs();
            tmpDir.mkdirs();
            if ("mp4".equals(hit.type)) {
                downloadDirect();
            } else {
                downloadHls();
            }
        } catch (Throwable t) {
            Log.e(TAG, "download failed", t);
            cb.onError(String.valueOf(t.getMessage()));
        } finally {
            deleteRec(tmpDir);
        }
    }

    // ---------------- direct mp4 ----------------
    private void downloadDirect() throws Exception {
        File out = uniqueFile(safeName(hit.title) + ".mp4");
        HttpURLConnection c = open(hit.url);
        int len = c.getContentLength();
        InputStream in = c.getInputStream();
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        long got = 0; int n;
        while ((n = in.read(buf)) > 0) {
            if (canceled) { in.close(); os.close(); out.delete(); cb.onError("中止"); return; }
            os.write(buf, 0, n); got += n;
            if (len > 0) cb.onProgress((int) (got * 100 / len), "DL " + (got >> 20) + "/" + (len >> 20) + "MB");
            else cb.onProgress(-1, "DL " + (got >> 20) + "MB");
        }
        os.close(); in.close(); c.disconnect();
        cb.onProgress(100, "完了");
        cb.onDone(out);
    }

    // ---------------- HLS ----------------
    private void downloadHls() throws Exception {
        cb.onProgress(-1, "解析中...");
        String playlistUrl = hit.url;
        String text = httpText(playlistUrl);

        // master playlist? → 最高画質を選ぶ + 別トラック音声(AUDIOグループ)を探す
        // （UI側で画質選択済みの場合はhit.urlがmedia playlistなのでここを素通りし、hit.audioUrlを使う）
        String audioUrl = hit.audioUrl;
        List<Variant> vars = listVariants(text, playlistUrl);
        if (!vars.isEmpty()) {
            Variant best = pickBest(vars);
            if (best.audioGroup != null) audioUrl = findAudioUri(text, playlistUrl, best.audioGroup);
            playlistUrl = best.url;
            text = httpText(playlistUrl);
            cb.onProgress(-1, "画質: " + best.label() + (audioUrl != null ? " +音声" : ""));
        } else if (hit.quality != null) {
            cb.onProgress(-1, "画質: " + hit.quality + (audioUrl != null ? " +音声" : ""));
        }

        Media v = parseMedia(text, playlistUrl);
        if (v.segs.isEmpty()) { cb.onError("セグメント無し"); return; }
        Media a = null;
        if (audioUrl != null) {
            a = parseMedia(httpText(audioUrl), audioUrl);
            if (a.segs.isEmpty()) a = null; // 空なら映像のみで続行
        }

        final int totalAll = v.segs.size() + (a != null ? a.segs.size() : 0);
        final AtomicInteger done = new AtomicInteger(0);

        File vFile = downloadStream(v, playlistUrl, "v", done, totalAll);
        if (vFile == null) return; // エラー通知済み
        File aFile = null;
        if (a != null) {
            aFile = downloadStream(a, audioUrl, "a", done, totalAll);
            if (aFile == null) return;
        }

        // mp4化（内部ストレージ上で変換 → 最後に1回だけ/sdcardへ書き出す）
        cb.onProgress(93, "mp4変換中...");
        File tmpMp4 = new File(tmpDir, "out.mp4");
        List<File> srcs = new ArrayList<>();
        srcs.add(vFile);
        if (aFile != null) srcs.add(aFile);
        boolean ok = remuxToMp4(srcs, tmpMp4, v.durUs);
        if (canceled) { cb.onError("中止"); return; }
        if (!ok && aFile != null) {
            // 音声合成に失敗したら映像のみで再試行（無音 > 失敗）
            Log.w(TAG, "A/V mux failed, retrying video only");
            ok = remuxToMp4(java.util.Collections.singletonList(vFile), tmpMp4, v.durUs);
        }
        File out;
        if (ok) {
            vFile.delete(); if (aFile != null) aFile.delete(); // 領域確保
            out = uniqueFile(safeName(hit.title) + ".mp4");
            cb.onProgress(99, "保存中...");
            copy(tmpMp4, out);
        } else {
            // 変換失敗時は映像の生データのまま保存。fMP4連結(init付き)は.mp4として再生可能
            boolean fmp4 = vFile.getName().endsWith(".mp4");
            out = uniqueFile(safeName(hit.title) + (fmp4 ? ".mp4" : ".ts"));
            cb.onProgress(99, "保存中...");
            copy(vFile, out);
        }
        cb.onProgress(100, "完了");
        cb.onDone(out);
    }

    /** 1ストリーム分のセグメントを並列DLして連結ファイルを返す。失敗時はonError通知してnull */
    private File downloadStream(Media m, String playlistUrl, String prefix,
                                AtomicInteger done, int totalAll) throws Exception {
        final AtomicReference<String> err = new AtomicReference<>(null);

        // AES鍵を取得（あれば）
        final byte[] key = (m.keyUri != null) ? httpBytes(absolute(m.keyUri, playlistUrl)) : null;

        // fMP4のinit segment（あれば）。セグメントより先に取得
        final byte[] init = (m.mapUri != null) ? httpBytesRetry(absolute(m.mapUri, playlistUrl), 3) : null;

        ExecutorService pool = Executors.newFixedThreadPool(CONC);
        final List<Seg> segs = m.segs;
        final int total = segs.size();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.execute(() -> {
                if (canceled || err.get() != null) return;
                try {
                    byte[] data = httpBytesRetry(segs.get(idx).url, 3);
                    if (key != null) data = decryptAes(data, key, segs.get(idx).iv);
                    File f = new File(tmpDir, prefix + idx + ".part");
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(data); fo.close();
                    int d = done.incrementAndGet();
                    if (d % 2 == 0 || d == totalAll)
                        cb.onProgress((int) (d * 90L / totalAll), "セグメントDL " + d + "/" + totalAll);
                } catch (Throwable t) {
                    err.compareAndSet(null, prefix + " seg " + idx + ": " + t.getMessage());
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.HOURS);

        if (canceled) { cb.onError("中止"); return null; }
        if (err.get() != null) { cb.onError(err.get()); return null; }

        // 連結（fMP4ならinitを先頭にプリペンド）
        cb.onProgress(91, "結合中...");
        File concat = new File(tmpDir, "all_" + prefix + (init != null ? ".mp4" : ".ts"));
        FileOutputStream fo = new FileOutputStream(concat);
        if (init != null) fo.write(init);
        byte[] buf = new byte[65536];
        for (int i = 0; i < total; i++) {
            File f = new File(tmpDir, prefix + i + ".part");
            InputStream is = new java.io.FileInputStream(f);
            int n; while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
            is.close(); f.delete();
        }
        fo.close();
        return concat;
    }

    // ---------------- m3u8 parse ----------------
    static class Variant {
        String url; long bw; String res; String audioGroup;
        /** 画質選択UI/通知用の表示ラベル */
        String label() {
            String mbps = String.format(java.util.Locale.US, "%.1fMbps", bw / 1e6);
            return res != null ? res + " (" + mbps + ")" : mbps;
        }
    }
    static class Seg { String url; byte[] iv; Seg(String u, byte[] iv){this.url=u;this.iv=iv;} }
    static class Media { List<Seg> segs = new ArrayList<>(); String keyUri; String mapUri; long durUs; }

    /** master playlistの全バリアントを列挙。media playlistなら空リスト */
    static List<Variant> listVariants(String text, String base) {
        List<Variant> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (ln.startsWith("#EXT-X-STREAM-INF")) {
                Variant v = new Variant();
                v.bw = parseLong(group(ln, "BANDWIDTH=(\\d+)"));
                v.res = group(ln, "RESOLUTION=(\\d+x\\d+)");
                v.audioGroup = group(ln, "AUDIO=\"([^\"]+)\"");
                String uri = (i + 1 < lines.length) ? lines[i + 1].trim() : "";
                if (!uri.isEmpty() && !uri.startsWith("#")) {
                    v.url = absolute(uri, base);
                    out.add(v);
                }
            }
        }
        return out;
    }

    static Variant pickBest(List<Variant> vars) {
        Variant best = null;
        for (Variant v : vars) if (best == null || v.bw > best.bw) best = v;
        return best;
    }

    /** マスタープレイリストから、バリアントのAUDIOグループに対応する音声プレイリストURIを探す */
    static String findAudioUri(String master, String base, String groupId) {
        String fallback = null;
        for (String raw : master.split("\\r?\\n")) {
            String ln = raw.trim();
            if (!ln.startsWith("#EXT-X-MEDIA") || !ln.contains("TYPE=AUDIO")) continue;
            String gid = group(ln, "GROUP-ID=\"([^\"]+)\"");
            if (!groupId.equals(gid)) continue;
            String uri = group(ln, "URI=\"([^\"]+)\"");
            if (uri == null) continue; // URI無し=音声はバリアント側に内包されている
            String abs = absolute(uri, base);
            if (ln.contains("DEFAULT=YES")) return abs;
            if (fallback == null) fallback = abs;
        }
        return fallback;
    }

    private Media parseMedia(String text, String base) {
        Media m = new Media();
        byte[] iv = null;
        int seq = 0;
        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            String ln = raw.trim();
            if (ln.isEmpty()) continue;
            if (ln.startsWith("#EXTINF")) {
                // 総再生時間を集計（remux進捗の分母に使う）
                String d = group(ln, "#EXTINF:([0-9.]+)");
                if (d != null) try { m.durUs += (long) (Double.parseDouble(d) * 1e6); } catch (Exception ignore) {}
            } else if (ln.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                seq = (int) parseLong(group(ln, "(\\d+)"));
            } else if (ln.startsWith("#EXT-X-MAP")) {
                // fMP4 HLS: init segment (moov)。これ無しではMediaExtractorが読めない
                m.mapUri = group(ln, "URI=\"([^\"]+)\"");
            } else if (ln.startsWith("#EXT-X-KEY")) {
                String method = group(ln, "METHOD=([A-Z0-9\\-]+)");
                if (method != null && method.contains("AES")) {
                    m.keyUri = unquote(group(ln, "URI=\"([^\"]+)\""));
                    String ivHex = group(ln, "IV=0[xX]([0-9A-Fa-f]+)");
                    if (ivHex != null) iv = hexToBytes(ivHex);
                } else {
                    m.keyUri = null; iv = null; // NONE
                }
            } else if (!ln.startsWith("#")) {
                byte[] segIv = iv;
                if (m.keyUri != null && segIv == null) segIv = seqToIv(seq);
                m.segs.add(new Seg(absolute(ln, base), segIv));
                seq++;
            }
        }
        return m;
    }

    // ---------------- AES-128 ----------------
    private byte[] decryptAes(byte[] data, byte[] key, byte[] iv) throws Exception {
        if (iv == null) iv = new byte[16];
        Cipher c;
        try {
            c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Exception e) {
            // パディング無しで再試行（実装差異対策）
            c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        }
    }

    private byte[] seqToIv(int seq) {
        byte[] iv = new byte[16];
        for (int i = 0; i < 4; i++) iv[15 - i] = (byte) (seq >>> (8 * i));
        return iv;
    }

    // ---------------- mp4 remux ----------------
    /** 複数ソース（映像/音声別ファイル）の全トラックを1つのmp4へremux。タイムスタンプ順にインターリーブ。
     *  durUs>0なら書き込み済みPTSベースで93→98%の進捗を通知する */
    private boolean remuxToMp4(List<File> srcs, File mp4, long durUs) {
        int ns = srcs.size();
        MediaExtractor[] exs = new MediaExtractor[ns];
        MediaMuxer mux = null;
        try {
            mux = new MediaMuxer(mp4.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[][] map = new int[ns][];
            long[][] aacFrameUs = new long[ns][]; // >0 ならAAC音声トラック（ADTS分割対象）
            int[][] aacSr = new int[ns][];        // AACサンプルレート（PTSグリッドスナップ用）
            int maxBuf = 1 << 20;
            boolean any = false;
            for (int s = 0; s < ns; s++) {
                exs[s] = new MediaExtractor();
                exs[s].setDataSource(srcs.get(s).getAbsolutePath());
                int nTracks = exs[s].getTrackCount();
                map[s] = new int[nTracks];
                aacFrameUs[s] = new long[nTracks];
                aacSr[s] = new int[nTracks];
                for (int i = 0; i < nTracks; i++) {
                    MediaFormat fmt = exs[s].getTrackFormat(i);
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        int sz = fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                        if (sz > maxBuf) maxBuf = sz;
                    }
                    String mime = fmt.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/mp4a")) {
                        // TS extractorはAACをADTSヘッダ付きで返すことがある。
                        // ADTSのままMP4へ書くとFDKデコーダが同期エラー→無音になるので分割対象に
                        int sr = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                                ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
                        aacFrameUs[s][i] = 1024L * 1000000L / sr;
                        aacSr[s][i] = sr;
                        if (!fmt.containsKey("csd-0")) {
                            byte[] csd = peekAacCsd(srcs.get(s).getAbsolutePath(), i);
                            if (csd != null) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                        }
                    }
                    map[s][i] = mux.addTrack(fmt);
                    exs[s].selectTrack(i);
                    any = true;
                }
            }
            if (!any) return false;
            mux.start();
            ByteBuffer buf = ByteBuffer.allocate(maxBuf);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean[] eos = new boolean[ns];
            int samples = 0; // 進捗通知の間引き用
            long[][] lastPts = new long[ns][]; // 音声トラックの単調増加強制用
            for (int s = 0; s < ns; s++) {
                lastPts[s] = new long[map[s].length];
                java.util.Arrays.fill(lastPts[s], Long.MIN_VALUE);
            }
            while (true) {
                if (canceled) return false;
                // 次サンプル時刻が最小のソースから書く（A/Vインターリーブ維持）
                int pick = -1; long min = Long.MAX_VALUE;
                for (int s = 0; s < ns; s++) {
                    if (eos[s]) continue;
                    long t = exs[s].getSampleTime();
                    if (t < 0) { eos[s] = true; continue; }
                    if (t < min) { min = t; pick = s; }
                }
                if (pick < 0) break;
                int n = exs[pick].readSampleData(buf, 0);
                if (n < 0) { eos[pick] = true; continue; }
                int track = exs[pick].getSampleTrackIndex();
                long pts = exs[pick].getSampleTime();
                if (aacFrameUs[pick][track] > 0 && n > 7
                        && (buf.get(0) & 0xFF) == 0xFF && (buf.get(1) & 0xF0) == 0xF0) {
                    // ADTSヘッダ付き（複数フレーム連結の可能性あり）→ rawフレームに分割して書く
                    lastPts[pick][track] = writeAdtsSplit(mux, map[pick][track], buf, n,
                            pts, aacSr[pick][track], lastPts[pick][track], info);
                } else {
                    // 音声はPTS重複でMediaMuxerが例外を投げる→単調増加を強制
                    if (aacFrameUs[pick][track] > 0 && pts <= lastPts[pick][track])
                        pts = lastPts[pick][track] + aacFrameUs[pick][track];
                    lastPts[pick][track] = pts;
                    info.offset = 0;
                    info.size = n;
                    info.presentationTimeUs = pts;
                    info.flags = exs[pick].getSampleFlags();
                    if (DBG_MUX && aacFrameUs[pick][track] > 0)
                        Log.d(TAG, "mux A trk=" + map[pick][track] + " pts=" + pts + " n=" + n);
                    mux.writeSampleData(map[pick][track], buf, info);
                }
                if (durUs > 0 && (++samples & 0xFF) == 0) {
                    long done = Math.min(pts, durUs);
                    cb.onProgress(93 + (int) (done * 5 / durUs), "mp4変換中 " + (done * 100 / durUs) + "%");
                }
                exs[pick].advance();
            }
            mux.stop();
            return mp4.length() > 0;
        } catch (Throwable t) {
            Log.e(TAG, "remux failed, fallback to raw", t);
            mp4.delete();
            return false;
        } finally {
            for (MediaExtractor ex : exs) if (ex != null) try { ex.release(); } catch (Exception ignore) {}
            if (mux != null) try { mux.release(); } catch (Exception ignore) {}
        }
    }

    /** ADTSフレーム列をヘッダを剥がしてrawフレーム単位でMP4へ書く（1フレーム=1024サンプル）。
     *  注意1: MediaMuxerはヒープバッファをバッキング配列の先頭基準(info.offset)で読み、
     *  slice()のarrayOffsetを無視する。必ず元バッファ+offsetで渡すこと。
     *  注意2: PTSは1024サンプルのフレームグリッドへ正確にスナップする。コンテナPTS(90kHz丸め)や
     *  切り捨てたframeUsの累積では実時間よりわずかに遅れ、MPEG4Writerのduration fudge
     *  (currDurationTicks==lastDurationTicks維持のため±100us内でtimestampUsを補正し
     *  lastTimestampUsに保持する)が内部時計を先行させ、次サンプルのbase復帰時に
     *  「out of order frames」でremuxが全滅する。グリッドスナップならticksが常に1024で
     *  fudge自体が発生しない。前回値以下になる場合のみ強制前進。戻り値は最後に書いたPTS。 */
    private static long writeAdtsSplit(MediaMuxer mux, int track, ByteBuffer buf, int len,
                                       long basePts, int sr, long lastPts,
                                       MediaCodec.BufferInfo info) {
        long frameUs = 1024L * 1000000L / sr;
        // basePtsに最も近いフレーム番号（グローバルグリッド）
        long g = (basePts * sr + 512L * 1000000L) / (1024L * 1000000L);
        int off = 0, idx = 0;
        while (off + 7 <= len) {
            int b0 = buf.get(off) & 0xFF, b1 = buf.get(off + 1) & 0xFF;
            if (b0 != 0xFF || (b1 & 0xF0) != 0xF0) break; // 同期喪失→以降は捨てる
            int hdr = ((b1 & 1) == 1) ? 7 : 9; // protection_absent=0ならCRC2バイト付き
            int fl = ((buf.get(off + 3) & 0x03) << 11)
                    | ((buf.get(off + 4) & 0xFF) << 3)
                    | ((buf.get(off + 5) & 0xFF) >>> 5);
            if (fl <= hdr || off + fl > len) break;
            long pts = ((g + idx) * 1024L * 1000000L + sr / 2) / sr;
            if (pts <= lastPts) pts = lastPts + frameUs;
            lastPts = pts;
            if (DBG_MUX) Log.d(TAG, "mux a trk=" + track + " pts=" + pts + " base=" + basePts
                    + " idx=" + idx + " off=" + off + " fl=" + fl);
            info.offset = off + hdr;
            info.size = fl - hdr;
            info.presentationTimeUs = pts;
            info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
            mux.writeSampleData(track, buf, info);
            off += fl;
            idx++;
        }
        return lastPts;
    }

    /** csd-0が無いAACトラック用に、先頭サンプルのADTSヘッダからAudioSpecificConfigを合成 */
    private static byte[] peekAacCsd(String path, int trackIdx) {
        MediaExtractor ex = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(path);
            ex.selectTrack(trackIdx);
            ByteBuffer b = ByteBuffer.allocate(16);
            if (ex.readSampleData(b, 0) < 7) return null;
            if ((b.get(0) & 0xFF) != 0xFF || (b.get(1) & 0xF0) != 0xF0) return null;
            int profile = ((b.get(2) & 0xC0) >> 6) + 1; // ADTS profile→AudioObjectType
            int freqIdx = (b.get(2) & 0x3C) >> 2;
            int chanCfg = ((b.get(2) & 0x01) << 2) | ((b.get(3) & 0xC0) >> 6);
            return new byte[]{
                    (byte) ((profile << 3) | (freqIdx >> 1)),
                    (byte) (((freqIdx & 1) << 7) | (chanCfg << 3))};
        } catch (Throwable t) {
            return null;
        } finally {
            if (ex != null) try { ex.release(); } catch (Exception ignore) {}
        }
    }

    // ---------------- http ----------------
    private HttpURLConnection open(String url) throws Exception { return open(url, hit); }

    private static HttpURLConnection open(String url, MediaHit hit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        if (hit.ua != null) c.setRequestProperty("User-Agent", hit.ua);
        if (hit.referer != null) c.setRequestProperty("Referer", hit.referer);
        if (hit.cookie != null) c.setRequestProperty("Cookie", hit.cookie);
        return c;
    }

    /** UI側の画質選択用: hitのヘッダ込みでplaylistテキストを取る */
    static String fetchText(String url, MediaHit hit) throws Exception {
        HttpURLConnection c = open(url, hit);
        InputStream in = c.getInputStream();
        byte[] b = readAll(in);
        in.close(); c.disconnect();
        return new String(b, "UTF-8");
    }

    private byte[] httpBytes(String url) throws Exception {
        HttpURLConnection c = open(url);
        InputStream in = c.getInputStream();
        byte[] b = readAll(in);
        in.close(); c.disconnect();
        return b;
    }

    private byte[] httpBytesRetry(String url, int retry) throws Exception {
        for (int i = 0; ; i++) {
            try { return httpBytes(url); }
            catch (Exception e) {
                if (i >= retry) throw e;
                Thread.sleep(500);
            }
        }
    }

    private String httpText(String url) throws Exception {
        return new String(httpBytes(url), "UTF-8");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ---------------- utils ----------------
    private File uniqueFile(String name) {
        File f = new File(outDir, name);
        if (!f.exists()) return f;
        String base = name.replaceAll("\\.[^.]+$", "");
        String ext = name.substring(base.length());
        for (int i = 1; i < 1000; i++) {
            File g = new File(outDir, base + "_" + i + ext);
            if (!g.exists()) return g;
        }
        return f;
    }

    private static String safeName(String s) {
        if (s == null || s.trim().isEmpty()) return "video_" + System.currentTimeMillis();
        s = s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static String absolute(String uri, String base) {
        try { return new URL(new URL(base), uri).toString(); }
        catch (Exception e) { return uri; }
    }

    private static String group(String s, String re) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String unquote(String s) { return s; }

    private static long parseLong(String s) {
        try { return s == null ? 0 : Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    private static byte[] hexToBytes(String h) {
        int len = h.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) Integer.parseInt(h.substring(i, i + 2), 16);
        return out;
    }

    private static void copy(File a, File b) throws Exception {
        InputStream in = new java.io.FileInputStream(a);
        OutputStream out = new FileOutputStream(b);
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); out.close();
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) deleteRec(k); }
        f.delete();
    }
}
