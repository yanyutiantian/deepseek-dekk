package com.dekk.yanyu;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 整包安装时的 Xposed 入口（assets/xposed_init 指向这里）。
 *
 * <p>真正的安装逻辑都在 {@link DekkEntry}，这样「整包安装」和「热更新壳跑 dex」
 * 走的是同一套代码，不会出现两套行为不一致。
 */
public class HookInit implements IXposedHookLoadPackage {

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		// 模块自身：用于界面显示“已激活”
		if (BuildConfig.APPLICATION_ID.equals(lpparam.packageName)) {
			DekkEntry.installSelf(lpparam.classLoader);
			return;
		}

		// 目标应用 DeepSeek
		if (DeepSeekSettingsHook.DEEPSEEK_PACKAGE.equals(lpparam.packageName)) {
			DekkEntry.install(lpparam.classLoader);
		}
	}

}