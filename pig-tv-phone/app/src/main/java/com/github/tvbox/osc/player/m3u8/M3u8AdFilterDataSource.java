package com.github.tvbox.osc.player.m3u8;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.github.tvbox.osc.util.m3u8.M3u8AdFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 会把 m3u8 里的广告段剔掉的 DataSource。
 *
 * <p>ExoPlayer 的 HLS 播放列表也是通过普通 DataSource 读进来的，所以只要把
 * {@link DataSource.Factory} 包一层，就能在列表文本到达解析器之前做改写 —— 不需要
 * 自己实现 HlsPlaylistParser，也不影响分片请求。
 *
 * <p>只在播放列表上动手：判断依据是 URL 里含 m3u8/m3u 且是从头读（position==0）。
 * 播放列表都很小，直接一次读完再交给下游；万一遇上「看着像播放列表、其实是几百兆
 * 大文件」的地址，读满 {@link #SNIFF_CAP} 就切回流式透传，不会把内存吃爆。
 */
public final class M3u8AdFilterDataSource implements DataSource {

    private static final String TAG = "M3u8Filter";
    /** 播放列表读取上限，超过就当成普通文件透传 */
    private static final int SNIFF_CAP = 6 * 1024 * 1024;

    /** 开关：每次建连时取一次，用户在设置里改了下一集就生效 */
    public interface Switch {
        boolean enabled();
    }

    /** 包一层工厂，其余照原样 */
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;
        private final Switch filterSwitch;

        public Factory(DataSource.Factory upstream, Switch filterSwitch) {
            this.upstream = upstream;
            this.filterSwitch = filterSwitch;
        }

        @Override
        public DataSource createDataSource() {
            return new M3u8AdFilterDataSource(upstream.createDataSource(), filterSwitch);
        }
    }

    private final DataSource upstream;
    private final Switch filterSwitch;

    @Nullable
    private Uri uri;
    @Nullable
    private byte[] buffer;
    private int buffered;
    private int pos;
    /** 已读完缓冲、后续要回落到上游流式读 */
    private boolean passthrough;

    public M3u8AdFilterDataSource(DataSource upstream, Switch filterSwitch) {
        this.upstream = upstream;
        this.filterSwitch = filterSwitch;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        long length = upstream.open(dataSpec);
        uri = upstream.getUri();
        buffer = null;
        buffered = 0;
        pos = 0;
        passthrough = true;

        if (filterSwitch == null || !filterSwitch.enabled() || !isPlaylistRequest(dataSpec)) {
            return length;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] tmp = new byte[16 * 1024];
        boolean eof = false;
        while (bos.size() < SNIFF_CAP) {
            int want = Math.min(tmp.length, SNIFF_CAP - bos.size());
            int n = upstream.read(tmp, 0, want);
            if (n == C.RESULT_END_OF_INPUT) {
                eof = true;
                break;
            }
            bos.write(tmp, 0, n);
        }
        byte[] raw = bos.toByteArray();
        if (!eof) {
            // 读不完就不猜了，原样给它，剩下的走流式
            buffer = raw;
            buffered = raw.length;
            passthrough = true;
            return C.LENGTH_UNSET;
        }

        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String out = M3u8AdFilter.strip(text);
        if (!out.equals(text)) {
            // 留一行日志：出问题时能立刻确认是过滤生效了还是源本身就少内容
            Log.i(TAG, "广告段已过滤: " + countSegments(text) + " → " + countSegments(out) + " 段 " + shortUri());
        }
        byte[] outBytes = out.equals(text) ? raw : out.getBytes(StandardCharsets.UTF_8);

        buffer = outBytes;
        buffered = outBytes.length;
        passthrough = false;
        return buffered;
    }

    @Override
    public int read(byte[] buf, int offset, int readLength) throws IOException {
        if (buffer != null && pos < buffered) {
            int n = Math.min(readLength, buffered - pos);
            System.arraycopy(buffer, pos, buf, offset, n);
            pos += n;
            return n;
        }
        if (passthrough) return upstream.read(buf, offset, readLength);
        return C.RESULT_END_OF_INPUT;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri != null ? uri : upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        buffer = null;
        buffered = 0;
        pos = 0;
        try {
            upstream.close();
        } finally {
            uri = null;
        }
    }

    private static boolean isPlaylistRequest(DataSpec dataSpec) {
        if (dataSpec.position != 0) return false;
        Uri u = dataSpec.uri;
        String s = u.toString().toLowerCase();
        return s.contains("m3u8") || s.contains(".m3u");
    }

    private static int countSegments(String content) {
        int n = 0;
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) n++;
        }
        return n;
    }

    private String shortUri() {
        if (uri == null) return "";
        String s = uri.toString();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
