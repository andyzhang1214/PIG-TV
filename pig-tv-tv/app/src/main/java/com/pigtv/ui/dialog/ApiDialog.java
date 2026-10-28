package com.pigtv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.pigtv.R;
import com.pigtv.event.RefreshEvent;
import com.pigtv.server.ControlManager;
import com.pigtv.ui.activity.HomeActivity;
import com.pigtv.ui.adapter.ApiHistoryDialogAdapter;
import com.pigtv.ui.tv.QRCodeGen;
import com.pigtv.util.DefaultConfig;
import com.pigtv.util.HawkConfig;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 描述
 *
 * @author pj567
 * @since 2020/12/27
 */
public class ApiDialog extends BaseDialog {
    private final ImageView ivQRCode;
    private final TextView tvAddress;
    private final EditText inputApi;
    private final EditText inputApiName;
    private final EditText inputLive;
    private final EditText inputLiveName;
    private final EditText inputEPG;

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) {
            inputApi.setText((String) event.obj);
            inputApiName.setText(Hawk.get(HawkConfig.API_NAME, ""));
        }
        if (event.type == RefreshEvent.TYPE_LIVE_URL_CHANGE) {
            inputLive.setText((String) event.obj);
            inputLiveName.setText(Hawk.get(HawkConfig.LIVE_NAME, ""));
        }
        if (event.type == RefreshEvent.TYPE_EPG_URL_CHANGE) {
            inputEPG.setText((String) event.obj);
        }
    }

    public ApiDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_api);
        setCanceledOnTouchOutside(true);
        ivQRCode = findViewById(R.id.ivQRCode);
        tvAddress = findViewById(R.id.tvAddress);
        inputApi = findViewById(R.id.input);
        inputApi.setText(Hawk.get(HawkConfig.API_URL, ""));
        inputApiName = findViewById(R.id.input_name);
        inputApiName.setText(Hawk.get(HawkConfig.API_NAME, ""));

        inputLive = findViewById(R.id.input_live);
        inputLive.setText(Hawk.get(HawkConfig.LIVE_URL, ""));
        inputLiveName = findViewById(R.id.input_live_name);
        inputLiveName.setText(Hawk.get(HawkConfig.LIVE_NAME, ""));
        inputEPG = findViewById(R.id.input_epg);
        // 节目单不填时也要让用户看见「实际用的就是哪个地址」，所以回显默认值而不是空框
        String savedEpg = Hawk.get(HawkConfig.EPG_URL, "");
        inputEPG.setText(isDefaultEpg(savedEpg) ? DEFAULT_EPG : savedEpg);

        // 推荐源胶囊：点一下 = 填入地址 + 直接走「确定」，少一步
        bindQuickSource(R.id.quickSrc0, QUICK[0]);
        bindQuickSource(R.id.quickSrc1, QUICK[1]);
        bindQuickSource(R.id.quickSrc2, QUICK[2]);

        findViewById(R.id.inputSubmit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSubmit();
            }
        });
        findViewById(R.id.apiHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
                if (history.isEmpty())
                    return;
                String current = Hawk.get(HawkConfig.API_URL, "");
                int idx = 0;
                if (history.contains(current))
                    idx = history.indexOf(current);
                ApiHistoryDialog dialog = new ApiHistoryDialog(getContext());
                dialog.setTip(HomeActivity.getRes().getString(R.string.dia_history_list));
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String value) {
                        inputApi.setText(value);
                        listener.onchange(value);
                        dialog.dismiss();
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        Hawk.put(HawkConfig.API_HISTORY, data);
                    }
                }, history, idx);
                dialog.show();
            }
        });
        findViewById(R.id.liveHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> liveHistory = Hawk.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
                if (liveHistory.isEmpty())
                    return;
                String current = Hawk.get(HawkConfig.LIVE_URL, "");
                int idx = 0;
                if (liveHistory.contains(current))
                    idx = liveHistory.indexOf(current);
                ApiHistoryDialog dialog = new ApiHistoryDialog(getContext());
                dialog.setTip(HomeActivity.getRes().getString(R.string.dia_history_live));
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String liveURL) {
                        inputLive.setText(liveURL);
                        Hawk.put(HawkConfig.LIVE_URL, liveURL);
                        dialog.dismiss();
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        Hawk.put(HawkConfig.LIVE_HISTORY, data);
                    }
                }, liveHistory, idx);
                dialog.show();
            }
        });
        findViewById(R.id.storagePermission).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (XXPermissions.isGranted(getContext(), DefaultConfig.StoragePermissionGroup())) {
                    Toast.makeText(getContext(), "已获得存储权限", Toast.LENGTH_SHORT).show();
                } else {
                    XXPermissions.with(getContext())
                            .permission(DefaultConfig.StoragePermissionGroup())
                            .request(new OnPermissionCallback() {
                                @Override
                                public void onGranted(List<String> permissions, boolean all) {
                                    if (all) {
                                        Toast.makeText(getContext(), "已获得存储权限", Toast.LENGTH_SHORT).show();
                                    }
                                }

                                @Override
                                public void onDenied(List<String> permissions, boolean never) {
                                    if (never) {
                                        Toast.makeText(getContext(), "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                                        XXPermissions.startPermissionActivity((Activity) getContext(), permissions);
                                    } else {
                                        Toast.makeText(getContext(), "获取存储权限失败", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                }
            }
        });
        refreshQRCode();
    }

    /**
     * 内置推荐源。地址由用户指定，原样写入，不自行替换。
     * 顺序 = 优先级：饭太硬是当前默认内置源（strings.xml 的 app_source），排第一。
     * 与手机端 assets/phone/index.html 里的 QUICK_SRC 保持同一份名单。
     */
    private static final String[][] QUICK = new String[][]{
            {"盒子迷·饭太硬", "https://盒子迷.top/饭太硬"},
            {"liucn 综合", "https://raw.liucn.cc/box/m.json"},
            {"盒子迷·禁止贩卖", "https://盒子迷.top/禁止贩卖"}
    };

    /** 把某个胶囊绑上推荐源：点一下 = 名字和地址一起填好 + 直接提交 */
    private void bindQuickSource(int id, final String[] src) {
        TextView tv = findViewById(id);
        if (tv == null) return;
        tv.setText(src[0]);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inputApi.setText(src[1]);
                inputApiName.setText(src[0]);
                doSubmit();
            }
        });
    }

    /** 确定 / 推荐源胶囊共用的落盘逻辑 */
    private void doSubmit() {
        String newApi = inputApi.getText().toString().trim();
        String newApiName = inputApiName.getText().toString().trim();
        String newLive = inputLive.getText().toString().trim();
        String newLiveName = inputLiveName.getText().toString().trim();
        String newEPG = inputEPG.getText().toString().trim();
        // takagen99: Convert all to clan://localhost format
        if (newApi.startsWith("file://")) {
            newApi = newApi.replace("file://", "clan://localhost/");
        } else if (newApi.startsWith("./")) {
            newApi = newApi.replace("./", "clan://localhost/");
        }
        saveName(HawkConfig.API_NAME, newApiName);
        saveName(HawkConfig.LIVE_NAME, newLiveName);
        if (!newApi.isEmpty()) {
            ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
            if (!history.contains(newApi))
                history.add(0, newApi);
            if (history.size() > 20)
                history.remove(20);
            Hawk.put(HawkConfig.API_HISTORY, history);
            if (listener != null) listener.onchange(newApi);
            dismiss();
        }
        // Capture Live input into Settings & Live History (max 20)
        Hawk.put(HawkConfig.LIVE_URL, newLive);
        if (!newLive.isEmpty()) {
            ArrayList<String> liveHistory = Hawk.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
            if (!liveHistory.contains(newLive))
                liveHistory.add(0, newLive);
            if (liveHistory.size() > 20)
                liveHistory.remove(20);
            Hawk.put(HawkConfig.LIVE_HISTORY, liveHistory);
        }
        // EPG 归到直播那一组，跟着一起存；填的等于默认值就不落盘，将来换默认地址能自动跟上
        if (isDefaultEpg(newEPG)) {
            Hawk.delete(HawkConfig.EPG_URL);
        } else {
            Hawk.put(HawkConfig.EPG_URL, newEPG);
            ArrayList<String> EPGHistory = Hawk.get(HawkConfig.EPG_HISTORY, new ArrayList<String>());
            if (!EPGHistory.contains(newEPG))
                EPGHistory.add(0, newEPG);
            if (EPGHistory.size() > 20)
                EPGHistory.remove(20);
            Hawk.put(HawkConfig.EPG_HISTORY, EPGHistory);
        }
    }

    /** 节目单默认值，必须和 LivePlayActivity 里 EPG_URL 为空时的回落值保持一致 */
    private static final String DEFAULT_EPG = "https://epg.112114.xyz/";

    /** 没存过 / 存的就是默认值，都按默认处理 */
    private boolean isDefaultEpg(String url) {
        if (url == null) return true;
        String u = url.trim();
        return u.isEmpty() || u.equals(DEFAULT_EPG);
    }

    /** 名字留空就删掉，界面上会回退成显示地址 */
    private void saveName(String key, String name) {
        if (name == null || name.isEmpty()) Hawk.delete(key);
        else Hawk.put(key, name);
    }

    private void refreshQRCode() {
        String address = ControlManager.get().getAddress(false);
        tvAddress.setText(String.format("手机扫码，或同一 WiFi 下用浏览器打开：\n%s", address));
        ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address, AutoSizeUtils.mm2px(getContext(), 260), AutoSizeUtils.mm2px(getContext(), 260)));
    }

    public void setOnListener(OnListener listener) {
        this.listener = listener;
    }

    OnListener listener = null;

    public interface OnListener {
        void onchange(String api);
    }
}