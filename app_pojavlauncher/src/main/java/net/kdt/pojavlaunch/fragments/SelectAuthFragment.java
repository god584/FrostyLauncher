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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

        private static class CookieEntry {
            final String domain;
            final String name;
            final String value;

            CookieEntry(String domain, String name, String value) {
                this.domain = domain.toLowerCase();
                this.name = name;
                this.value = value;
            }

            boolean matchesHost(String host) {
                if (domain.isEmpty()) return true;
                String h = host.toLowerCase();
                String d = domain.startsWith(".") ? domain.substring(1) : domain;
                return h.equals(d) || h.endsWith("." + d);
            }
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
                List<CookieEntry> jar = parseCookies(rawNetscape);
                if (jar.isEmpty()) {
                    postError(callback, "Invalid cookie format");
                    return;
                }

                String authUrl = "https://login.live.com/oauth20_authorize.srf"
                        + "?client_id=00000000402b5328"
                        + "&redirect_uri=https://login.live.com/oauth20_desktop.srf"
                        + "&response_type=token"
                        + "&scope=service::user.auth.xboxlive.com::MBI_SSL";

                String msaToken = resolveMsaToken(authUrl, jar);
                if (msaToken == null || msaToken.isEmpty()) {
                    postError(callback, "Could not obtain MSA access_token from cookies");
                    return;
                }

                JSONObject xblPayload = new JSONObject();
                JSONObject xblProps = new JSONObject();
                xblProps.put("AuthMethod", "RPS");
                xblProps.put("SiteName", "user.auth.xboxlive.com");
                xblProps.put("RpsTicket", "d=" + msaToken);
                xblPayload.put("Properties", xblProps);
                xblPayload.put("RelyingParty", "http://auth.xboxlive.com");
                xblPayload.put("TokenType", "JWT");

                String xblResp = httpPostJson("https://user.auth.xboxlive.com/user/authenticate", xblPayload.toString());
                JSONObject xblJson = new JSONObject(xblResp);
                String xblToken = xblJson.getString("Token");
                String uhs = xblJson.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");

                JSONObject xstsPayload = new JSONObject();
                JSONObject xstsProps = new JSONObject();
                xstsProps.put("SandboxId", "RETAIL");
                xstsProps.put("UserTokens", new JSONArray().put(xblToken));
                xstsPayload.put("Properties", xstsProps);
                xstsPayload.put("RelyingParty", "rp://api.minecraftservices.com/");
                xstsPayload.put("TokenType", "JWT");

                String xstsResp = httpPostJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsPayload.toString());
                JSONObject xstsJson = new JSONObject(xstsResp);
                String xstsToken = xstsJson.getString("Token");

                JSONObject mcAuthPayload = new JSONObject();
                mcAuthPayload.put("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);

                String mcAuthResp = httpPostJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcAuthPayload.toString());
                JSONObject mcAuthJson = new JSONObject(mcAuthResp);
                String mcAccessToken = mcAuthJson.getString("access_token");

                String profileResp = httpGetWithBearer("https://api.minecraftservices.com/minecraft/profile", mcAccessToken);
                JSONObject profileJson = new JSONObject(profileResp);
                if (!profileJson.has("name") || !profileJson.has("id")) {
                    postError(callback, "Account does not own Minecraft Java");
                    return;
                }

                String username = profileJson.getString("name");
                String uuid = profileJson.getString("id");

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
        private static String resolveMsaToken(String startUrl, List<CookieEntry> jar) throws Exception {
            String currentUrl = startUrl;
            String referer = "https://login.live.com/";

            for (int hop = 0; hop < 10; hop++) {
                String tokenInUrl = extractAccessToken(currentUrl);
                if (tokenInUrl != null) return tokenInUrl;

                URL url = new URL(currentUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                conn.setRequestProperty("Referer", referer);

                String cookieHeader = buildCookieForHost(jar, url.getHost());
                if (!cookieHeader.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookieHeader);
                }

                int code = conn.getResponseCode();
                storeResponseCookies(jar, url.getHost(), conn.getHeaderFields());

                String location = conn.getHeaderField("Location");
                if (location != null) {
                    String tokenInLoc = extractAccessToken(location);
                    if (tokenInLoc != null) return tokenInLoc;
                    referer = currentUrl;
                    currentUrl = new URL(url, location).toString();
                    continue;
                }

                String body = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
                String tokenInBody = extractAccessToken(body);
                if (tokenInBody != null) return tokenInBody;

                String formToken = trySubmitHiddenForm(body, currentUrl, jar);
                if (formToken != null) return formToken;

                if (code >= 400) {
                    throw new Exception("MSA HTTP " + code);
                }
                break;
            }
            return null;
        }

        private static String trySubmitHiddenForm(String html, String baseUrl, List<CookieEntry> jar) throws Exception {
            if (html == null || !html.contains("<form")) return null;
            Matcher actionMatcher = Pattern.compile("<form[^>]+action=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            if (!actionMatcher.find()) return null;

            String actionUrl = new URL(new URL(baseUrl), actionMatcher.group(1)).toString();
            Matcher inputMatcher = Pattern.compile("<input[^>]+name=[\"']([^\"']+)[\"'][^>]+value=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);

            StringBuilder formBody = new StringBuilder();
            while (inputMatcher.find()) {
                if (formBody.length() > 0) formBody.append('&');
                formBody.append(URLEncoder.encode(inputMatcher.group(1), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(inputMatcher.group(2), "UTF-8"));
            }
            if (formBody.length() == 0) return null;

            URL url = new URL(actionUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", baseUrl);

            String cookieHeader = buildCookieForHost(jar, url.getHost());
            if (!cookieHeader.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(formBody.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            storeResponseCookies(jar, url.getHost(), conn.getHeaderFields());
            String location = conn.getHeaderField("Location");
            if (location != null) {
                String tokenInLoc = extractAccessToken(location);
                if (tokenInLoc != null) return tokenInLoc;
                return resolveMsaToken(new URL(url, location).toString(), jar);
            }
            String body = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            return extractAccessToken(body);
        }

        private static List<CookieEntry> parseCookies(String raw) {
            List<CookieEntry> list = new ArrayList<>();
            for (String line : raw.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\t");
                if (parts.length >= 7) {
                    list.add(new CookieEntry(parts[0].trim(), parts[5].trim(), parts[6].trim()));
                } else if (trimmed.contains("=")) {
                    for (String pair : trimmed.split(";")) {
                        int idx = pair.indexOf('=');
                        if (idx > 0) {
                            list.add(new CookieEntry("", pair.substring(0, idx).trim(), pair.substring(idx + 1).trim()));
                        }
                    }
                }
            }
            return list;
        }

        private static String buildCookieForHost(List<CookieEntry> jar, String host) {
            Map<String, String> merged = new LinkedHashMap<>();
            for (CookieEntry entry : jar) {
                if (entry.matchesHost(host)) {
                    merged.put(entry.name, entry.value);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : merged.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            return sb.toString();
        }

        private static void storeResponseCookies(List<CookieEntry> jar, String host, Map<String, List<String>> headers) {
            if (headers == null) return;
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Set-Cookie") && entry.getValue() != null) {
                    for (String sc : entry.getValue()) {
                        String firstPair = sc.split(";")[0];
                        int idx = firstPair.indexOf('=');
                        if (idx > 0) {
                            jar.add(new CookieEntry(host, firstPair.substring(0, idx).trim(), firstPair.substring(idx + 1).trim()));
                        }
                    }
                }
            }
        }

        private static String extractAccessToken(String text) {
            if (text == null || text.isEmpty()) return null;
            try {
                Matcher m = Pattern.compile("access_token=([^&\"'\\s#]+)").matcher(text);
                if (m.find()) return URLDecoder.decode(m.group(1), "UTF-8");
                Matcher mJson = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
                if (mJson.find()) return mJson.group(1);
            } catch (Exception ignored) {
            }
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

        private static String httpGetWithBearer(String urlStr, String bearer) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + bearer);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            String body = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) throw new Exception("Profile HTTP " + code);
            return body;
        }

        private static String httpPostJson(String urlStr, String jsonBody) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String body = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) throw new Exception("Auth HTTP " + code);
            return body;
        }

        private static String readStream(InputStream is) throws Exception {
            if (is == null) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }

        private static void postError(LoginCallback callback, String msg) {
            MAIN_HANDLER.post(() -> callback.onError(msg));
        }
    }
            }
