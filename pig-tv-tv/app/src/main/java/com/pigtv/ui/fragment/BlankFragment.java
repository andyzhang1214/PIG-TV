package com.pigtv.ui.fragment;

import com.pigtv.R;
import com.pigtv.base.BaseLazyFragment;

/**
 * 空白占位页。
 *
 * 分类页的 ViewPager 与站点 sort 列表一一对应（下标对齐，切分类就是 setCurrentItem(i)），
 * 但「首页推荐(my0)」这一格既不该出现原版 UserFragment 的旧快捷入口，也不能空出位置
 * 让后面的分类整体错位——所以塞一个什么都不画的空页面在这里。
 */
public class BlankFragment extends BaseLazyFragment {

    public static BlankFragment newInstance() {
        return new BlankFragment();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_blank;
    }

    @Override
    protected void init() {
    }
}
