package com.pigtv.ui.activity;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pigtv.R;
import com.pigtv.base.App;
import com.pigtv.base.BaseActivity;
import com.pigtv.server.ControlManager;
import com.pigtv.server.RemoteServer;
import com.pigtv.ui.adapter.LiveSourceAdapter;
import com.pigtv.ui.dialog.LiveSourceAddDialog;
import com.pigtv.ui.dialog.TipDialog;
import com.pigtv.util.LiveSourceStore;
import com.pigtv.util.V8TopBar;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.List;

/**
 * 直播源管理页（频道表右上角「配置」进来）。
 *
 * <p>解决的是「怎么换源」这一件事。之前只能靠手机浏览器开 9978 端口填地址，
 * 换回上一条得把 URL 重新敲一遍；遥控器上这不是难，是不可能。
 *
 * <p>切换源要重启整个 app，不是为了省事 —— {@code Hawk(LIVE_URL)} 是 ApiConfig
 * 在启动时一次性算进 channelGroupList 的，进程内改它不会让已解析好的频道表跟着变。
 * 网页遥控台保存后也是重启（{@link ControlManager} 里那段），两处行为保持一致，
 * 用户不需要记「哪里改完要等一下」。重启只需要 1~2 秒，换源是低频操作。
 */
public class LiveSourceActivity extends BaseActivity {

    /** 抢焦点的最大重试次数：窗口焦点常常比 onCreate 的 post 来得晚 */
    private static final int MAX_FOCUS_TRY = 8;

    private TvRecyclerView mSourceList;
    private TextView tvCurStat;
    private TextView tvWebHint;
    private View btnAdd;

    private LiveSourceAdapter adapter;

    /** 删除确认框。字段持有只是为了能在监听器里关掉它 */
    private TipDialog tipDialog;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** 正在重启，别再响应按键（否则连点两下会切两次源） */
    private boolean restarting = false;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_live_source;
    }

    @Override
    protected void init() {
        V8TopBar.attachClockOnly(this);

        tvCurStat = findViewById(R.id.tvCurStat);
        tvWebHint = findViewById(R.id.tvWebHint);
        btnAdd = findViewById(R.id.btnAdd);

        mSourceList = findViewById(R.id.mSourceList);
        mSourceList.setHasFixedSize(true);
        mSourceList.setLayoutManager(new V7LinearLayoutManager(mContext, LinearLayoutManager.VERTICAL, false));
        adapter = new LiveSourceAdapter();
        mSourceList.setAdapter(adapter);
        adapter.setOnActionListener(new LiveSourceAdapter.OnActionListener() {
            @Override
            public void onPick(LiveSourceStore.Source source) {
                onSourcePicked(source);
            }

            @Override
            public void onDelete(LiveSourceStore.Source source) {
                confirmDelete(source);
            }
        });
        // 列表最后一行按 ↓ 交给「添加」按钮。默认的 focusSearch 从 RecyclerView
        // 内部往上/下找，常常直接跳到底部按钮再跳到别的容器，落点不稳定。
        mSourceList.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_DOWN) {
                    btnAdd.requestFocus();
                    return true;
                }
                return false;
            }
        });

        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddDialog();
            }
        });
        // 从「添加」按 ↑ 回列表最后一行；不接管的话会跳到标题行上去
        btnAdd.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return focusLastRow();
                }
                return false;
            }
        });

        // 手机网页那条路要真能用，得把本地服务起起来。原版在 HomeActivity 里做，
        // 独立直播版没有 HomeActivity —— 之前一直没人调，9978 端口根本听不到。
        // 必须先起服务再拼网址：端口被占时 serverPort 会自增，先取会拼出错的端口。
        try {
            ControlManager.get().startServer();
        } catch (Throwable th) {
            // 端口全占满之类的极端情况：只影响手机网页入口，页面本身照常用
            th.printStackTrace();
        }

        tvWebHint.setText(getString(R.string.v8_src_web_hint, webAddress()));

        refresh();
        focusInitial(0);
    }

    // =====================================================================
    //  数据
    // =====================================================================

    private void refresh() {
        List<LiveSourceStore.Source> data = LiveSourceStore.all();
        adapter.setNewData(data);

        String curUrl = LiveSourceStore.currentUrl();
        String curName = null;
        for (LiveSourceStore.Source s : data) {
            if (s.url.trim().equals(curUrl)) {
                curName = s.displayName();
                break;
            }
        }
        if (TextUtils.isEmpty(curName)) {
            curName = TextUtils.isEmpty(curUrl)
                    ? getString(R.string.v8_src_tag_builtin)
                    : curUrl;
        }
        tvCurStat.setText(getString(R.string.v8_src_current_stat, curName));
    }

    // =====================================================================
    //  切换 / 删除 / 添加
    // =====================================================================

    private void onSourcePicked(final LiveSourceStore.Source source) {
        if (source == null || restarting) return;
        if (LiveSourceStore.isCurrent(source)) {
            Toast.makeText(mContext, "已经在用这一条", Toast.LENGTH_SHORT).show();
            return;
        }
        LiveSourceStore.apply(source);
        restartWithTip(getString(R.string.v8_src_applied, source.displayName()));
    }

    /**
     * 删一条：先弹确认。
     *
     * <p>TipDialog 点完按钮不会自己关（它原本是给调用方自己收尾的），
     * 所以这里必须自己 dismiss，否则按完「删除」那个框一直挂在屏上。
     * 焦点默认留给「取消」——遥控器一按 OK 就把源删了实在太顺手了。
     *
     * <p>删掉的正好是当前在用的那条：LiveSourceStore.remove 已经把它回退到内置源，
     * 但进程里 ApiConfig 的频道表还是旧源解析出来的，只能重启一次才干净。
     */
    private void confirmDelete(final LiveSourceStore.Source source) {
        if (source == null || restarting || source.isBuiltin()) return;

        // 用字段持有对话框而不是局部变量：匿名内部类里引局部变量，javac 会报
        // 「可能尚未初始化」（它没法证明监听器不会在构造期间被调到）。
        tipDialog = new TipDialog(this, R.layout.dialog_tip_v8,
                getString(R.string.v8_src_del_msg, source.displayName()),
                getString(R.string.v8_src_delete),
                getString(R.string.v8_src_dlg_cancel),
                new TipDialog.OnListener() {
                    @Override
                    public void left() {
                        dismissTip();
                        boolean wasCurrent = LiveSourceStore.isCurrent(source);
                        if (!LiveSourceStore.remove(source.url)) return;
                        if (wasCurrent) {
                            restartWithTip(getString(R.string.v8_src_applied,
                                    getString(R.string.v8_src_tag_builtin)));
                        } else {
                            refresh();
                            Toast.makeText(mContext, "已删除", Toast.LENGTH_SHORT).show();
                            // 删完那一行连同它的「删除」按钮一起没了，焦点跟着消失，
                            // 遥控器按什么都没反应。把焦点落到现在的最后一行。
                            focusLastRow();
                        }
                    }

                    @Override
                    public void right() {
                        dismissTip();
                    }

                    @Override
                    public void cancel() {
                        // 按 BACK 关掉，什么都不做
                    }
                });
        tipDialog.show();
        View cancel = tipDialog.findViewById(R.id.rightBtn);
        if (cancel != null) cancel.requestFocus();
    }

    private void dismissTip() {
        if (tipDialog != null && tipDialog.isShowing()) tipDialog.dismiss();
        tipDialog = null;
    }

    private void showAddDialog() {
        LiveSourceAddDialog dialog = new LiveSourceAddDialog(this, webAddress());
        dialog.setOnSubmitListener(new LiveSourceAddDialog.OnSubmitListener() {
            @Override
            public void onSubmit(String name, String url) {
                if (!LiveSourceStore.add(name, url)) {
                    Toast.makeText(mContext, R.string.v8_src_bad_url, Toast.LENGTH_SHORT).show();
                    return;
                }
                LiveSourceStore.Source target = null;
                for (LiveSourceStore.Source s : LiveSourceStore.custom()) {
                    if (s.url.trim().equals(url.trim())) {
                        target = s;
                        break;
                    }
                }
                if (target == null) {
                    target = new LiveSourceStore.Source(name, url);
                }
                LiveSourceStore.apply(target);
                restartWithTip(getString(R.string.v8_src_added_switch, target.displayName()));
            }
        });
        dialog.show();
    }

    /**
     * 落盘之后的重启。
     *
     * <p>为什么必须重启：{@code Hawk(LIVE_URL)} 是 ApiConfig 启动时一次性算进
     * channelGroupList 的，进程内改它不会让已解析好的频道表跟着变。网页遥控台
     * 保存后也是重启（ControlManager 里那段），两处行为一致，用户不用记
     * 「哪里改完要等一下」。换源是低频操作，1~2 秒可以接受。
     *
     * @param tip 已经组织好的提示文案（「已切换到 X」/「已删除，回退内置源」等）
     */
    private void restartWithTip(String tip) {
        if (restarting) return;
        restarting = true;
        Toast.makeText(mContext, tip, Toast.LENGTH_SHORT).show();

        // 1.2 秒：让 Toast 露个脸，也给刚关掉的确认框留点收尾时间
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    }
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                System.exit(0);
            }
        }, 1200);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (restarting) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 关掉还在屏上的确认框，否则窗口泄漏（Activity 已经没了，Window 还挂着）
        if (tipDialog != null && tipDialog.isShowing()) tipDialog.dismiss();
        tipDialog = null;
    }

    // =====================================================================
    //  焦点
    // =====================================================================

    /**
     * 让列表某一项拿到焦点。
     *
     * <p>必须能重试：onCreate 里发的 post 跑到时窗口往往还没拿到焦点，
     * requestFocus() 会安静地返回 false，用户进来后方向键按下去毫无反应。
     */
    private void focusInitial(final int attempt) {
        mSourceList.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (isInside(mSourceList)) return;
                if (attempt >= MAX_FOCUS_TRY) {
                    mSourceList.requestFocus();
                    return;
                }
                RecyclerView.ViewHolder holder = mSourceList.findViewHolderForAdapterPosition(0);
                if (holder != null && holder.itemView.requestFocus()) return;

                mSourceList.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        focusInitial(attempt + 1);
                    }
                }, 120);
            }
        });
    }

    /** 「添加」按 ↑ 时把焦点送回列表最后一行 */
    private boolean focusLastRow() {
        final int last = adapter.getItemCount() - 1;
        if (last < 0) return false;
        RecyclerView.ViewHolder holder = mSourceList.findViewHolderForAdapterPosition(last);
        if (holder != null && holder.itemView.requestFocus()) return true;
        mSourceList.scrollToPosition(last);
        mSourceList.post(new Runnable() {
            @Override
            public void run() {
                RecyclerView.ViewHolder h = mSourceList.findViewHolderForAdapterPosition(last);
                if (h != null) h.itemView.requestFocus();
                else mSourceList.requestFocus();
            }
        });
        return true;
    }

    private boolean isInside(View box) {
        View f = getCurrentFocus();
        if (f == null || box == null) return false;
        View p = f;
        while (p != null) {
            if (p == box) return true;
            android.view.ViewParent vp = p.getParent();
            p = vp instanceof View ? (View) vp : null;
        }
        return false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && !restarting && !isInside(mSourceList) && !isInside(btnAdd)) {
            focusInitial(0);
        }
    }

    // =====================================================================

    /** 手机网页入口：拿不到 IP 就退回「电视 IP:9978」，别让提示空着 */
    private String webAddress() {
        try {
            String ip = RemoteServer.getLocalIPAddress(App.getInstance());
            if (!TextUtils.isEmpty(ip) && !"0.0.0.0".equals(ip)) {
                return "http://" + ip + ":" + RemoteServer.serverPort + "/";
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return "http://电视IP:" + RemoteServer.serverPort + "/";
    }
}
