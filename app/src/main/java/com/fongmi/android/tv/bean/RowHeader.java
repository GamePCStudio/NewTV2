package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;

/**
 * 列表栏目标题。
 *
 * <p>以前标题直接以字符串资源 id（{@code Integer}）放进适配器，文字固定。
 * 「来自 · 站源名」这类需要<b>运行时拼接</b>的标题没法用资源 id 表达，于是引入这个模型：
 * {@link #key} 仍是字符串资源 id，用作稳定标识（查找、替换、去重），
 * {@link #text} 是最终展示的文字。</p>
 */
public class RowHeader {

    private final int key;
    private final CharSequence text;

    public RowHeader(int key, CharSequence text) {
        this.key = key;
        this.text = text;
    }

    public int getKey() {
        return key;
    }

    public CharSequence getText() {
        return text == null ? "" : text;
    }

    @NonNull
    @Override
    public String toString() {
        return getText().toString();
    }
}
