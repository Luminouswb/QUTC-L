package com.qutc.launcher.ui.pages;

import javafx.scene.Node;

/** 一个页面 = 一个根节点 + 生命周期回调。 */
public interface Page {

    String id();

    String title();

    Node view();

    /** 页面切到前台时调用（刷新数据用）。 */
    default void onShow() {
    }
}
