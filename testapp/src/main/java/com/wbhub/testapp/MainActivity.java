package com.wbhub.testapp;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 逆向工具的测试样本：界面只有两部分。
 *
 * 上半部分的结果来自 C（libwbtest.so 的 JNI 函数），下半部分来自 Java，
 * 便于验证 dex 反编译和 .so 反汇编这两条链路。
 */
public class MainActivity extends Activity {

    static {
        System.loadLibrary("wbtest");
    }

    private TextView cOutput;
    private TextView javaOutput;

    private native String nativeReport();

    private native int nativeSum(int n);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));
        root.setBackgroundColor(Color.WHITE);

        TextView heading = new TextView(this);
        heading.setText("WBHub 逆向测试样本");
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, 0, 0, dp(24));
        root.addView(heading);

        root.addView(sectionTitle("C 部分 · libwbtest.so"));
        cOutput = bodyText();
        root.addView(cOutput);
        Button cButton = actionButton("调用 C");
        cButton.setOnClickListener(v -> cOutput.setText(callNative()));
        root.addView(cButton, buttonParams());

        root.addView(sectionTitle("Java 部分"));
        javaOutput = bodyText();
        root.addView(javaOutput);
        Button javaButton = actionButton("调用 Java");
        javaButton.setOnClickListener(v -> javaOutput.setText(JavaPart.report()));
        root.addView(javaButton, buttonParams());

        setContentView(root);

        cOutput.setText(callNative());
        javaOutput.setText(JavaPart.report());
    }

    private String callNative() {
        return nativeReport()
                + "\nnativeSum(10) = " + nativeSum(10)
                + "\nnativeSum(100) = " + nativeSum(100);
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private TextView bodyText() {
        TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(Color.DKGRAY);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(24);
        return params;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
