package com.winlator.core;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.container.Drive;
import com.winlator.win32.MSLogFont;
import com.winlator.win32.WinVersions;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

public abstract class WineUtils {
    public static void createDosdevicesSymlinks(Container container, boolean addDriveCDRom) {
        File rootDir = container.getRootDir();
        String dosdevicesPath = (new File(rootDir, ".wine/dosdevices")).getPath();
        File[] files = (new File(dosdevicesPath)).listFiles();
        if (files != null) for (File file : files) if (file.getName().matches("[a-z]:")) file.delete();

        FileUtils.symlink("../drive_c", dosdevicesPath+"/c:");
        FileUtils.symlink("../../../../", dosdevicesPath+"/z:");

        if (addDriveCDRom) {
            File driveX = new File(rootDir, ".wine/drive_x");
            if (!driveX.isDirectory()) {
                driveX.mkdir();
                FileUtils.chmod(driveX, 0771);
            }

            String serial = String.format(Locale.ENGLISH, "%-8x", (int)'X').replace(' ', '0');
            FileUtils.writeString(new File(driveX, ".windows-serial"), serial+"\n");
            FileUtils.symlink("../drive_x", dosdevicesPath+"/x:");
        }

        for (Drive drive : container.drivesIterator()) {
            File linkTarget = new File(drive.path);
            String path = linkTarget.getAbsolutePath();
            if (!linkTarget.isDirectory() && path.startsWith(AppUtils.INTERNAL_STORAGE)) {
                linkTarget.mkdirs();
                FileUtils.chmod(linkTarget, 0771);
            }
            FileUtils.symlink(path, dosdevicesPath+"/"+drive.letter.toLowerCase(Locale.ENGLISH)+":");
        }
    }

    public static void setSystemFont(WineRegistryEditor userRegistry, String faceName) {
        byte[] fontNormalData = (new MSLogFont()).setFaceName(faceName).toByteArray();
        byte[] fontBoldData = (new MSLogFont()).setFaceName(faceName).setWeight(700).toByteArray();
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "CaptionFont", fontBoldData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "IconFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "MenuFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "MessageFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "SmCaptionFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "StatusFont", fontNormalData);
    }

    public static void applySystemTweaks(Context context, WineInfo wineInfo) {
        File rootDir = RootFS.find(context).getRootDir();

        File userCacheDir = new File(rootDir, RootFS.USER_CACHE_PATH);
        if (!userCacheDir.isDirectory()) userCacheDir.mkdirs();
        File userConfigDir = new File(rootDir, RootFS.USER_CONFIG_PATH);
        if (!userConfigDir.isDirectory()) userConfigDir.mkdirs();

        File systemRegFile = new File(rootDir, RootFS.WINEPREFIX+"/system.reg");
        File userRegFile = new File(rootDir, RootFS.WINEPREFIX+"/user.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setStringValue("Software\\Wine\\Drives", "x:", "cdrom");
            registryEditor.setStringValue("Software\\Classes\\.reg", null, "REGfile");
            registryEditor.setStringValue("Software\\Classes\\.reg", "Content Type", "application/reg");
            registryEditor.setStringValue("Software\\Classes\\REGfile\\Shell\\Open\\command", null, "C:\\windows\\regedit.exe /C \"%1\"");

            registryEditor.setStringValue("Software\\Classes\\dllfile\\DefaultIcon", null, "shell32.dll,-154");
            registryEditor.setStringValue("Software\\Classes\\lnkfile\\DefaultIcon", null, "shell32.dll,-30");
            registryEditor.setStringValue("Software\\Classes\\inifile\\DefaultIcon", null, "shell32.dll,-151");

            File corefontsAddedFile = new File(userConfigDir, "corefonts.added");
            if (!corefontsAddedFile.isFile()) {
                setupSystemFonts(registryEditor);
                FileUtils.writeString(corefontsAddedFile, String.valueOf(System.currentTimeMillis()));
            }
        }

        final String[] direct3dLibs = {"d3d8", "d3d9", "d3d10", "d3d10_1", "d3d10core", "d3d11", "d3d12", "d3d12core", "ddraw", "dxgi", "wined3d"};
        final String dllOverridesKey = "Software\\Wine\\DllOverrides";

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            for (String name : direct3dLibs) registryEditor.setStringValue(dllOverridesKey, name, "native,builtin");

            registryEditor.removeKey("Software\\Winlator\\WFM\\ContextMenu\\7-Zip");
            registryEditor.setStringValue("Software\\Winlator\\WFM\\ContextMenu\\7-Zip", "Open Archive", "Z:\\opt\\apps\\7-Zip\\7zFM.exe \"%FILE%\"");
            registryEditor.setStringValue("Software\\Winlator\\WFM\\ContextMenu\\7-Zip", "Extract Here", "Z:\\opt\\apps\\7-Zip\\7zG.exe x \"%FILE%\" -r -o\"%DIR%\" -y");
            registryEditor.setStringValue("Software\\Winlator\\WFM\\ContextMenu\\7-Zip", "Extract to Folder", "Z:\\opt\\apps\\7-Zip\\7zG.exe x \"%FILE%\" -r -o\"%DIR%\\%BASENAME%\" -y");
            registryEditor.setStringValue("Software\\Wine\\AddonsURL", null, "https://raw.githubusercontent.com/brunodev85/winlator/main/wine_addons/");
            registryEditor.setStringValue("Software\\Wine\\Drivers", "Graphics", "x11");
        }
    }

    public static void changeBrowsersRegistryKey(Container container, boolean useAndroidBrowser) {
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (useAndroidBrowser) {
                registryEditor.setStringValue("Software\\Wine\\WineBrowser", "Browsers", "C:\\windows\\winhandler.exe /url");
            }
            else registryEditor.setStringValue("Software\\Wine\\WineBrowser", "Browsers", "C:\\windows\\system32\\iexplore.exe");
        }
    }

    public static void overrideWinComponentDlls(Context context, Container container, String wincomponents) {
        final String dllOverridesKey = "Software\\Wine\\DllOverrides";
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(context, "wincomponents/wincomponents.json"));

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                JSONObject wincomponentJSONObject = wincomponentsJSONObject.getJSONObject(identifier);
                JSONArray dlnames = wincomponentJSONObject.getJSONArray("dlnames");
                for (int i = 0; i < dlnames.length(); i++) {
                    String dlname = dlnames.getString(i);
                    if (useNative) {
                        registryEditor.setStringValue(dllOverridesKey, dlname, "native,builtin");
                    }
                    else registryEditor.removeValue(dllOverridesKey, dlname);
                }
            }
        }
        catch (JSONException e) {}
    }

    public static void setWinComponentRegistryKeys(File systemRegFile, String identifier, boolean useNative) {
        if (identifier.equals("directsound")) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
                final String key64 = "Software\\Classes\\CLSID\\{083863F1-70DE-11D0-BD40-00A0C911CE86}\\Instance\\{E30629D1-27E5-11CE-875D-00608CB78066}";
                final String key32 = "Software\\Classes\\Wow6432Node\\CLSID\\{083863F1-70DE-11D0-BD40-00A0C911CE86}\\Instance\\{E30629D1-27E5-11CE-875D-00608CB78066}";

                if (useNative) {
                    registryEditor.setStringValue(key32, "CLSID", "{E30629D1-27E5-11CE-875D-00608CB78066}");
                    registryEditor.setHexValue(key32, "FilterData", "02000000000080000100000000000000307069330200000000000000010000000000000000000000307479330000000038000000480000006175647300001000800000aa00389b710100000000001000800000aa00389b71");
                    registryEditor.setStringValue(key32, "FriendlyName", "Wave Audio Renderer");

                    registryEditor.setStringValue(key64, "CLSID", "{E30629D1-27E5-11CE-875D-00608CB78066}");
                    registryEditor.setHexValue(key64, "FilterData", "02000000000080000100000000000000307069330200000000000000010000000000000000000000307479330000000038000000480000006175647300001000800000aa00389b710100000000001000800000aa00389b71");
                    registryEditor.setStringValue(key64, "FriendlyName", "Wave Audio Renderer");
                }
                else {
                    registryEditor.removeKey(key32);
                    registryEditor.removeKey(key64);
                }
            }
        }
        else if (identifier.equals("wmdecoder")) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
                if (useNative) {
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{2EEB4ADF-4578-4D10-BCA7-BB955F56320A}\\InprocServer32", null, "C:\\windows\\syswow64\\wmadmod.dll");
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{82D353DF-90BD-4382-8BC2-3F6192B76E34}\\InprocServer32", null, "C:\\windows\\syswow64\\wmvdecod.dll");
                }
                else {
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{2EEB4ADF-4578-4D10-BCA7-BB955F56320A}\\InprocServer32", null, "C:\\windows\\syswow64\\winegstreamer.dll");
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{82D353DF-90BD-4382-8BC2-3F6192B76E34}\\InprocServer32", null, "C:\\windows\\syswow64\\winegstreamer.dll");
                }
            }
        }
    }

    public static void updateWineprefix(Context context, final Callback<Integer> terminationCallback) {
        RootFS rootFS = RootFS.find(context);
        final File rootDir = rootFS.getRootDir();
        File tmpDir = rootFS.getTmpDir();
        if (!tmpDir.isDirectory()) tmpDir.mkdir();

        FileUtils.writeString(new File(rootDir, RootFS.WINEPREFIX+"/.update-timestamp"), "0\n");

        EnvVars envVars = new EnvVars();
        envVars.put("WINEPREFIX", rootDir+RootFS.WINEPREFIX);
        envVars.put("WINEDLLOVERRIDES", "mscoree,mshtml=d");

        XEnvironment environment = new XEnvironment(context, rootFS);
        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();
        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setGuestExecutable("wine wineboot -u");
        guestProgramLauncherComponent.setTerminationCallback((status) -> {
            FileUtils.writeString(new File(rootDir, RootFS.WINEPREFIX+"/.update-timestamp"), "disable\n");
            if (terminationCallback != null) terminationCallback.call(status);
        });
        environment.addComponent(guestProgramLauncherComponent);
        environment.startEnvironmentComponents();
    }

    public static boolean isWineprefixWasUpdated(Container container) {
        File file = new File(container.getRootDir(), "/.wine/.update-timestamp");
        String content = FileUtils.readString(file);
        
        if (!content.startsWith("disable")) {
            content = content.replaceAll("[\r\n]+", "");
            try {
                int updateTimestamp = Integer.parseInt(content);
                if (updateTimestamp != 0) return FileUtils.writeString(file, "disable\n");
            }
            catch (NumberFormatException e) {}
        }
        return false;
    }

    public static void changeServicesStatus(Container container, byte startupSelection) {
        final byte SERVICE_DISABLED = 4;
        final String[] services = {"BITS:3", "Eventlog:2", "HTTP:3", "LanmanServer:3", "NDIS:2", "PlugPlay:2", "RpcSs:3", "scardsvr:3", "Schedule:3", "Spooler:3", "StiSvc:3", "TermService:3", "Winmgmt:3", "wuauserv:3", "winebth:3"};
        final String[] extraServices = {"nsiproxy:2", "MSIServer:3", "FontCache:3"};
        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setCreateKeyIfNotExist(false);

            String controlSetPath = registryEditor.getSymlinkValue("System\\CurrentControlSet", "SymbolicLinkValue");
            if (controlSetPath == null) controlSetPath = "System\\CurrentControlSet";

            for (String service : services) {
                String name = service.substring(0, service.indexOf(":"));
                int value = startupSelection != Container.STARTUP_SELECTION_NORMAL ? SERVICE_DISABLED : Character.getNumericValue(service.charAt(service.length()-1));
                registryEditor.setDwordValue(controlSetPath+"\\Services\\"+name, "Start", value);
            }

            for (String service : extraServices) {
                String name = service.substring(0, service.indexOf(":"));
                int value = startupSelection == Container.STARTUP_SELECTION_AGGRESSIVE ? SERVICE_DISABLED : Character.getNumericValue(service.charAt(service.length()-1));
                registryEditor.setDwordValue(controlSetPath+"\\Services\\"+name, "Start", value);
            }
        }
    }

    public static String unixToDOSPath(String unixPath, Container container) {
        String dosPath = "";
        String driveLetter = "";

        for (Drive drive : container.drivesIterator()) {
            if (unixPath.startsWith(drive.path)) {
                driveLetter = drive.letter+":";
                dosPath = unixPath.substring(drive.path.length()).replace("/", "\\");
                break;
            }
        }

        if (dosPath.isEmpty()) {
            int index = unixPath.indexOf("/.wine/drive_c");
            if (index != -1) {
                driveLetter = "C:";
                dosPath = unixPath.substring(index + 14).replace("/", "\\");
            }
        }

        if (!dosPath.startsWith("\\")) dosPath += "\\";
        dosPath = driveLetter+StringUtils.removeEndSlash(dosPath);
        if (dosPath.equals(driveLetter)) dosPath += "\\";
        return dosPath;
    }

    public static String dosToUnixPath(String dosPath, Container container) {
        int index = dosPath.indexOf(":");
        if (index == -1) return "";

        String unixPath = "";
        String driveLetter = dosPath.substring(0, index).toUpperCase(Locale.ENGLISH);
        String relativePath = StringUtils.removeStartSlash(dosPath.substring(index+1).replace("\\", "/"));

        if (driveLetter.equals("C")) {
            unixPath = container.getRootDir()+"/.wine/drive_c/"+relativePath;
        }
        else if (driveLetter.equals("Z")) {
            File rootDir = new File(container.getRootDir(), "../../");
            try {
                unixPath = rootDir.getCanonicalPath()+"/"+relativePath;
            }
            catch (IOException e) {}
        }
        else {
            for (Drive drive : container.drivesIterator()) {
                if (drive.letter.equals(driveLetter)) {
                    unixPath = drive.path+"/"+relativePath;
                    break;
                }
            }
        }

        return unixPath;
    }

    public static void setWinVersion(Container container, int winVersionIdx) {
        WinVersions.WinVersion winVersion = WinVersions.getWinVersions()[winVersionIdx];
        String currentBuild = String.valueOf(winVersion.buildNumber);
        String currentVersion = winVersion.currentVersion != null ? winVersion.currentVersion : winVersion.majorVersion+"."+winVersion.minorVersion;

        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            String key64 = "Software\\Microsoft\\Windows NT\\CurrentVersion";
            String key32 = "Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion";

            registryEditor.setStringValue(key32, "CurrentVersion", currentVersion);
            registryEditor.setDwordValue(key32, "CurrentMajorVersionNumber", winVersion.majorVersion);
            registryEditor.setDwordValue(key32, "CurrentMinorVersionNumber", winVersion.minorVersion);
            registryEditor.setStringValue(key32, "CSDVersion", winVersion.csdVersion);
            registryEditor.setStringValue(key32, "CurrentBuild", currentBuild);
            registryEditor.setStringValue(key32, "CurrentBuildNumber", currentBuild);
            registryEditor.setStringValue(key32, "ProductName", "Microsoft "+winVersion.description);

            registryEditor.setStringValue(key64, "CurrentVersion", currentVersion);
            registryEditor.setDwordValue(key64, "CurrentMajorVersionNumber", winVersion.majorVersion);
            registryEditor.setDwordValue(key64, "CurrentMinorVersionNumber", winVersion.minorVersion);
            registryEditor.setStringValue(key64, "CSDVersion", winVersion.csdVersion);
            registryEditor.setStringValue(key64, "CurrentBuild", currentBuild);
            registryEditor.setStringValue(key64, "CurrentBuildNumber", currentBuild);
            registryEditor.setStringValue(key64, "ProductName", "Microsoft "+winVersion.description);
        }
    }

    private static void setupSystemFonts(WineRegistryEditor registryEditor) {
        final String[][] corefonts = {
            {"Andale Mono (TrueType)", "andalemo.ttf"},
            {"Arial (TrueType)", "arial.ttf"},
            {"Arial Black (TrueType)", "ariblk.ttf"},
            {"Arial Bold (TrueType)", "arialbd.ttf"},
            {"Arial Bold Italic (TrueType)", "arialbi.ttf"},
            {"Arial Italic (TrueType)", "ariali.ttf"},
            {"Comic Sans MS (TrueType)", "comic.ttf"},
            {"Comic Sans MS Bold (TrueType)", "comicbd.ttf"},
            {"Courier New (TrueType)", "cour.ttf"},
            {"Courier New Bold (TrueType)", "courbd.ttf"},
            {"Courier New Bold Italic (TrueType)", "courbi.ttf"},
            {"Courier New Italic (TrueType)", "couri.ttf"},
            {"Georgia (TrueType)", "georgia.ttf"},
            {"Georgia Bold (TrueType)", "georgiab.ttf"},
            {"Georgia Bold Italic (TrueType)", "georgiaz.ttf"},
            {"Georgia Italic (TrueType)", "georgiai.ttf"},
            {"Impact (TrueType)", "impact.ttf"},
            {"Times New Roman (TrueType)", "times.ttf"},
            {"Times New Roman Bold (TrueType)", "timesbd.ttf"},
            {"Times New Roman Bold Italic (TrueType)", "timesbi.ttf"},
            {"Times New Roman Italic (TrueType)", "timesi.ttf"},
            {"Trebuchet MS (TrueType)", "trebuc.ttf"},
            {"Trebuchet MS Bold (TrueType)", "trebucbd.ttf"},
            {"Trebuchet MS Bold Italic (TrueType)", "trebucbi.ttf"},
            {"Trebuchet MS Italic (TrueType)", "trebucit.ttf"},
            {"Verdana (TrueType)", "verdana.ttf"},
            {"Verdana Bold (TrueType)", "verdanab.ttf"},
            {"Verdana Bold Italic (TrueType)", "verdanaz.ttf"},
            {"Verdana Italic (TrueType)", "verdanai.ttf"},
            {"Webdings (TrueType)", "webdings.ttf"}
        };

        registryEditor.setStringValues("Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", corefonts);
        registryEditor.setStringValues("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", corefonts);

        final String[][] wineFonts = {
            {"Marlett (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\marlett.ttf"},
            {"Symbol (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\symbol.ttf"},
            {"Tahoma (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\tahoma.ttf"},
            {"Tahoma Bold (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\tahomabd.ttf"},
            {"Wingdings (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\wingding.ttf"}
        };

        registryEditor.setStringValues("Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", wineFonts);
        registryEditor.setStringValues("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", wineFonts);
    }

    /**
     * 把 Android 系统自带的 CJK 字体装入容器并在注册表登记常见中文字体名映射，
     * 解决 Wine 对话框与中文程序文本因缺少中文字形而显示成方框（tofu）的问题。
     * 字体文件复制到 rootfs 全局字体目录（所有容器共享），中文字体注册表写入
     * 当前容器的 system.reg。
     *
     * 该方法在每次容器启动时调用（setupWineSystemFiles），内部幂等：
     * 只要字体文件已存在且标记已写就跳过；任一缺失都会重做以自愈旧容器。
     */
    public static void setupCJKFonts(Context context, Container container) {
        File rootDir = RootFS.find(context).getRootDir();
        File userConfigDir = new File(rootDir, RootFS.USER_CONFIG_PATH);
        File cjkFontsAddedFile = new File(userConfigDir, "cjkfonts.added");
        File destDir = new File(rootDir, "/opt/wine/share/wine/fonts");
        File destFile = new File(destDir, "notosanscjk.ttc");

        // 双重判断：标记存在 AND 字体文件实际存在，才跳过字体复制。否则重做以自愈旧容器。
        if (!(cjkFontsAddedFile.isFile() && destFile.isFile() && destFile.length() > 0)) {
            // 按优先级检测 Android 系统 CJK 字体（isFile 会跟随 symlink 判断真实文件）
            File srcFile = null;
            for (String path : new String[]{
                "/system/fonts/NotoSansCJK-Regular.ttc",
                "/system/fonts/NotoSansSC-Regular.otf",
                "/system/fonts/NotoSerifCJK-Regular.ttc",
                "/system/fonts/DroidSansFallback.ttf",
                "/system/fonts/NotoSansCJK-Regular.ttc.otf"
            }) {
                File candidate = new File(path);
                if (candidate.isFile()) { srcFile = candidate; break; }
            }
            if (srcFile == null) {
                // 找不到系统 CJK 字体，写标记避免每次启动都扫描系统字体目录
                FileUtils.writeString(cjkFontsAddedFile, String.valueOf(System.currentTimeMillis()));
                return;
            }

            // 复制到 rootfs 全局字体目录，所有容器共享。
            // 注意：不能用 FileUtils.copy，它在源为 symlink 时会跳过复制；
            // Android 系统 CJK 字体常以 symlink 形式存在，这里用流式复制读取真实内容。
            if (!destDir.isDirectory()) destDir.mkdirs();
            if (!destFile.isFile() || destFile.length() == 0) {
                destFile.delete();
                boolean ok = copyFontFileByStream(srcFile, destFile);
                if (!ok) return; // 复制失败，不写标记，下次启动重试
            }
            FileUtils.writeString(cjkFontsAddedFile, String.valueOf(System.currentTimeMillis()));
        }

        // 注册表写入当前容器的 system.reg（通过 container.getRootDir() 定位）。
        // 每次都写，保证旧容器即使之前没写入也能补上；WineRegistryEditor 是幂等的。
        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");
        String fontPath = "Z:\\opt\\wine\\share\\wine\\fonts\\notosanscjk.ttc";
        // 同时登记中文字体名和 Noto 自身 face 名。
        // Noto face 名必须登记，否则 Replacements 替换后 Wine 仍找不到目标字体。
        // ttc 含 SC/TC/JP/KR 多 face，Wine 按 face 名匹配加载对应子字体。
        final String[][] cjkFonts = {
            {"SimSun (TrueType)", fontPath},
            {"NSimSun (TrueType)", fontPath},
            {"SimHei (TrueType)", fontPath},
            {"Microsoft YaHei (TrueType)", fontPath},
            {"Microsoft YaHei Bold (TrueType)", fontPath},
            {"Microsoft JhengHei (TrueType)", fontPath},
            {"KaiTi (TrueType)", fontPath},
            {"FangSong (TrueType)", fontPath},
            {"Noto Sans CJK SC (TrueType)", fontPath},
            {"Noto Sans CJK TC (TrueType)", fontPath},
            {"Noto Sans CJK JP (TrueType)", fontPath},
            {"Noto Sans CJK KR (TrueType)", fontPath}
        };
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setStringValues("Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", cjkFonts);
            registryEditor.setStringValues("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", cjkFonts);
        }

        // 关键：在 user.reg 写入 Fonts\Replacements，把应用可能请求的各种中文字体名
        // （含中文别名"宋体""黑体""微软雅黑"等）全部替换为我们已注册的 SimSun。
        // 目标用 SimSun 而非 Noto face 名，因为 SimSun 在上面 Fonts 注册表里一定登记过，
        // 无论 ttc 真实 face 名是 Noto Sans CJK SC 还是 Noto Sans SC，替换链都能闭合。
        // 没有这一步，galgame 用中文名请求字体会找不到，Wine 不回退直接显示方框。
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        final String[][] replacements = {
            {"NSimSun", "SimSun"},
            {"SimHei", "SimSun"},
            {"Microsoft YaHei", "SimSun"},
            {"Microsoft YaHei UI", "SimSun"},
            {"Microsoft JhengHei", "SimSun"},
            {"Microsoft JhengHei UI", "SimSun"},
            {"KaiTi", "SimSun"},
            {"FangSong", "SimSun"},
            // 中文别名
            {"宋体", "SimSun"},
            {"新宋体", "SimSun"},
            {"黑体", "SimSun"},
            {"微软雅黑", "SimSun"},
            {"楷体", "SimSun"},
            {"仿宋", "SimSun"},
            // 日文/韩文也兜底到同一字体（Noto Sans CJK 含日韩字形）
            {"MS Gothic", "SimSun"},
            {"MS PGothic", "SimSun"},
            {"MS UI Gothic", "SimSun"},
            {"Malgun Gothic", "SimSun"}
        };
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            registryEditor.setStringValues("Software\\Wine\\Fonts\\Replacements", replacements);
        }
    }

    /**
     * 通过字节流复制字体文件，绕过 FileUtils.copy 对 symlink 的跳过逻辑。
     * 系统字体可能是 symlink，FileInputStream 会跟随到真实文件读取内容。
     */
    private static boolean copyFontFileByStream(File srcFile, File destFile) {
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(srcFile);
            out = new java.io.FileOutputStream(destFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            out.flush();
            return destFile.length() > 0;
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (!destFile.isFile() || destFile.length() == 0) destFile.delete();
        }
    }
}
