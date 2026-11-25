package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SelectAuthFragment extends Fragment {
    public static final String TAG = "AUTH_SELECT_FRAGMENT";

    public SelectAuthFragment() {
        super(R.layout.fragment_select_auth_method);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View mMicrosoftButton = view.findViewById(R.id.button_microsoft_authentication);
        View mElybyButton = view.findViewById(R.id.button_elyby_authentication);
        View mLocalButton = view.findViewById(R.id.button_local_authentication);
        View mCookieButton = view.findViewById(R.id.button_cookie_authentication);

        mMicrosoftButton.setOnClickListener(v -> navigateTo(MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG, null));

        mElybyButton.setOnClickListener(v -> navigateTo(ElybyLoginFragment.class, ElybyLoginFragment.TAG, null));

        mLocalButton.setOnClickListener(v -> hasNoOnlineProfileDialog(requireActivity(),
                () -> navigateTo(LocalLoginFragment.class, LocalLoginFragment.TAG, null)));

        if (mCookieButton != null) {
            mCookieButton.setOnClickListener(v -> CookieAuthHelper.showCookieDialog(requireActivity(), new CookieAuthHelper.LoginCallback() {
                @Override
                public void onSuccess(MinecraftAccount account) {
                    if (!isAdded()) return;
                    ExtraCore.setValue(ExtraConstants.ACCOUNT_CHANGED, account.username);
                    Toast.makeText(getContext(), "Logged in: " + account.username, Toast.LENGTH_SHORT).show();
                    if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                        getParentFragmentManager().popBackStack();
                    }
                }

                @Override
                public void onError(String reason) {
                    if (!isAdded()) return;
                    Toast.makeText(getContext(), "Login failed: " + reason, Toast.LENGTH_LONG).show();
                }
            }));
        }

        float rise = 18f * getResources().getDisplayMetrics().density;
        animateIn(view.findViewById(R.id.auth_header), 0, rise);
        animateIn(mMicrosoftButton, 90, rise);
        animateIn(mElybyButton, 180, rise);
        animateIn(mLocalButton, 270, rise);
        animateIn(mCookieButton, 315, rise);
        animateIn(view.findViewById(R.id.auth_footnote), 360, rise);
    }

    private void animateIn(@Nullable View v, long delayMs, float rise) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(rise);
        v.setScaleX(0.95f);
        v.setScaleY(0.95f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delayMs)
                .setDuration(430)
                .setInterpolator(net.kdt.pojavlaunch.utils.animation.MotionCurves.SPRING_SOFT)
                .start();
    }

    private void navigateTo(Class<? extends Fragment> cls, String tag, Bundle args) {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openChildPane(cls, tag, args);
        } else {
            Tools.swapFragment(requireActivity(), cls, tag, args);
        }
    }

    private static class CookieAuthHelper {
        interface LoginCallback {
            void onSuccess(MinecraftAccount account);
            void onError(String reason);
        }

        private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
        private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

        static void showCookieDialog(Activity activity, LoginCallback callback) {
            Context context = activity;
            EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMinLines(5);
            input.setHint("Paste Netscape cookies here...");

            new AlertDialog.Builder(context)
                    .setTitle("Cookie Login")
                    .setView(input)
                    .setPositiveButton("Login", (dialog, which) -> {
                        String raw = input.getText().toString().trim();
                        if (raw.isEmpty()) {
                            callback.onError("Empty cookie input");
                            return;
                        }
                        Toast.makeText(context, "Authenticating...", Toast.LENGTH_SHORT).show();
                        EXECUTOR.execute(() -> authenticateWithCookie(raw, callback));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        private static void authenticateWithCookie(String rawNetscape, LoginCallback callback) {
            try {
                String cookieHeader = buildCookieHeader(rawNetscape);
                if (cookieHeader.isEmpty()) {
                    postError(callback, "Invalid Netscape cookie format");
                    return;
                }

                // Step 1: Check Microsoft / Live / SISU session with cookies
                String liveHtml = httpGet("https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&redirect_uri=https://login.live.com/oauth20_desktop.srf&response_type=token&scope=service::user.auth.xboxlive.com::MBI_SSL",
                        cookieHeader, "https://login.live.com/");

                String msaToken = extractAccessToken(liveHtml);
                if (msaToken == null) {
                    // Fallback: try SISU / Xbox live authorize endpoint
                    String sisuBody = httpGet("https://sisu.xboxlive.com/connect/authorize/?client_id=00000000402b5328&redirect_uri=https://login.live.com/oauth20_desktop.srf&response_type=token&scope=service::user.auth.xboxlive.com::MBI_SSL",
                            cookieHeader, "https://www.minecraft.net/");
                    msaToken = extractAccessToken(sisuBody);
                }

                if (msaToken == null || msaToken.isEmpty()) {
                    postError(callback, "Session expired or missing MSA auth cookies");
                    return;
                }

                // Step 2: MSA -> Xbox Live User Token
                JSONObject xblPayload = new JSONObject();
                JSONObject xblProps = new JSONObject();
                xblProps.put("AuthMethod", "RPS");
                xblProps.put("SiteName", "user.auth.xboxlive.com");
                xblProps.put("RpsTicket", "d=" + msaToken);
                xblPayload.put("Properties", xblProps);
                xblPayload.put("RelyingParty", "http://auth.xboxlive.com");
                xblPayload.put("TokenType", "JWT");

                String xblResp = httpPostJson("https://user.auth.xboxlive.com/user/authenticate", xblPayload.toString(), null);
                JSONObject xblJson = new JSONObject(xblResp);
                String xblToken = xblJson.getString("Token");
                String uhs = xblJson.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");

                // Step 3: XBL -> XSTS Token for Minecraft Services
                JSONObject xstsPayload = new JSONObject();
                JSONObject xstsProps = new JSONObject();
                xstsProps.put("SandboxId", "RETAIL");
                xstsProps.put("UserTokens", new JSONArray().put(xblToken));
                xstsPayload.put("Properties", xstsProps);
                xstsPayload.put("RelyingParty", "rp://api.minecraftservices.com/");
                xstsPayload.put("TokenType", "JWT");

                String xstsResp = httpPostJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsPayload.toString(), null);
                JSONObject xstsJson = new JSONObject(xstsResp);
                String xstsToken = xstsJson.getString("Token");

                // Step 4: XSTS -> Minecraft Services Bearer Token
                JSONObject mcAuthPayload = new JSONObject();
                mcAuthPayload.put("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);

                String mcAuthResp = httpPostJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcAuthPayload.toString(), null);
                JSONObject mcAuthJson = new JSONObject(mcAuthResp);
                String mcAccessToken = mcAuthJson.getString("access_token");

                // Step 5: Fetch Minecraft Profile (Username + UUID)
                String profileResp = httpGetWithBearer("https://api.minecraftservices.com/minecraft/profile", mcAccessToken);
                JSONObject profileJson = new JSONObject(profileResp);
                if (!profileJson.has("name") || !profileJson.has("id")) {
                    postError(callback, "Account does not own Minecraft or profile not found");
                    return;
                }

                String username = profileJson.getString("name");
                String uuid = profileJson.getString("id");

                // Step 6: Create & Save MinecraftAccount
                MinecraftAccount account = new MinecraftAccount();
                account.username = username;
                account.profileId = uuid;
                account.accessToken = mcAccessToken;
                account.isMicrosoft = true;
                saveAccountJson(account);

                MAIN_HANDLER.post(() -> callback.onSuccess(account));
            } catch (Exception e) {
                postError(callback, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }

        private static String buildCookieHeader(String raw) {
            Map<String, String> cookies = new LinkedHashMap<>();
            String[] lines = raw.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\t");
                if (parts.length >= 7) {
                    cookies.put(parts[5].trim(), parts[6].trim());
                } else if (trimmed.contains("=")) {
                    for (String pair : trimmed.split(";")) {
                        int idx = pair.indexOf('=');
                        if (idx > 0) {
                            cookies.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
                        }
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
            return sb.toString();
        }

        private static String extractAccessToken(String text) {
            if (text == null || text.isEmpty()) return null;
            Matcher m = Pattern.compile("access_token=([^&\"'\\s]+)").matcher(text);
            if (m.find()) return m.group(1);
            Matcher mJson = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
            if (mJson.find()) return mJson.group(1);
            return null;
        }

        private static void saveAccountJson(MinecraftAccount account) throws Exception {
            File dir = new File(Tools.DIR_ACCOUNT_NEW);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            JSONObject obj = new JSONObject();
            obj.put("username", account.username);
            obj.put("profileId", account.profileId);
            obj.put("accessToken", account.accessToken);
            obj.put("isMicrosoft", true);

            File outFile = new File(dir, account.username + ".json");
            Tools.write(outFile.getAbsolutePath(), obj.toString(2));
        }

        private static String httpGet(String urlStr, String cookieHeader, String referer) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }
            if (referer != null) {
                conn.setRequestProperty("Referer", referer);
            }
            return readResponse(conn);
        }

        private static String httpGetWithBearer(String urlStr, String bearer) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + bearer);
            conn.setRequestProperty("Accept", "application/json");
            return readResponse(conn);
        }

        private static String httpPostJson(String urlStr, String jsonBody, String cookieHeader) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            return readResponse(conn);
        }

        private static String readResponse(HttpURLConnection conn) throws Exception {
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            String body = sb.toString();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + ": " + body);
            }
            return body;
        }

        private static void postError(LoginCallback callback, String msg) {
            MAIN_HANDLER.post(() -> callback.onError(msg));
        }
    }
                }
