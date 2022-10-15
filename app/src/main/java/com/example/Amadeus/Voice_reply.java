package com.example.Amadeus;


import android.media.MediaPlayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Voice_reply {
    void send(String input) {
        //开启线程，发送请求
        new Thread(new Runnable() {
            // 把其他语言转换成日语
            public String trans_jp(String input) {
                String url = "https://fanyi.youdao.com/translate_o?smartresult=dict&smartresult=rule";
                OkHttpClient client = new OkHttpClient();
                String lts = String.valueOf(System.currentTimeMillis());
                String salt = lts + "6";
                String sign = "fanyideskweb" + input + salt + "Y2FYu%TNSbMCxc3t2u^XT";
                // 对sign进行md5加密（java好麻烦……）
                try {
                    MessageDigest m = MessageDigest.getInstance("MD5");
                    m.update(sign.getBytes("UTF8"));
                    byte[] s = m.digest();
                    sign = "";
                    for (byte b : s) {
                        sign += Integer.toHexString((0x000000ff & b) | 0xffffff00).substring(6);
                    }
                } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
                    e.printStackTrace();
                }

                FormBody payload = new FormBody.Builder()
                        .add("i", input)
                        .add("from", "AUTO")
                        .add("to", "ja")
                        .add("smartresult", "dict")
                        .add("client", "fanyideskweb")
                        .add("salt", salt)
                        .add("sign", sign)
                        .add("lts", lts)
                        .add("bv", "361d3a19799ae6678a2de9a8326248a7")
                        .add("doctype", "json")
                        .add("version", "2.1")
                        .add("keyfrom", "fanyi.web")
                        .add("action", "FY_BY_REALTlME")
                        .build();
                Request request = new Request.Builder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.71 Safari/537.36 Edg/94.0.992.38")
                        .header("Referer", "https://fanyi.youdao.com/")
                        .header("Cookie", "OUTFOX_SEARCH_USER_ID=1735571180@10.108.160.105; OUTFOX_SEARCH_USER_ID_NCOO=2126344143.7370343; JSESSIONID=aaaUnf4skKhuG5udkMLXx; ___rl__test__cookies=1633792089428")
                        .post(payload)
                        .url(url)
                        .build();
                Call call = client.newCall(request);
                String res = null;
                try {
                    Response response = call.execute();
                    String res_info = response.body().string();
                    JSONObject res_json_obj = new JSONObject(res_info);
                    res = res_json_obj.getJSONArray("translateResult").getJSONArray(0).getJSONObject(0).getString("tgt");
                } catch (IOException | JSONException e) {
                    e.printStackTrace();
                }
                return res;
            }

            @Override
            public void run() {
                // 获取Amadeus语音数据并播放
                String input_jp = trans_jp(input);
                String url = "https://api-inference.huggingface.co/models/mio/amadeus";
                OkHttpClient client = new OkHttpClient();
                // payload 以json形式传递（与formdata区别）
                JSONObject json = new JSONObject();
                try {
                    json.put("inputs",input_jp);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                //okhttp传json时需要转换为String类型的json数据
                MediaType mediaType=MediaType.Companion.parse("application/json;charset=utf-8");
                RequestBody requestBody=RequestBody.Companion.create(String.valueOf(json),mediaType);
                final Request request = new Request.Builder()
                        // 请求头 键值对
                        .header("content-type","application/json")
                        .header("Authorization","Bearer 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")
                        .url(url)
                        .post(requestBody)
                        .build();
                Call call = client.newCall(request);
                try {
                    Response response = call.execute();
                    InputStream voice_byte = response.body().byteStream();
                    File tempVoice = File.createTempFile("voice", ".flac");
                    tempVoice.deleteOnExit();
                    FileOutputStream fos = new FileOutputStream(tempVoice);

                    int byteRead;
                    while((byteRead=voice_byte.read()) != -1){
                        fos.write(byteRead);
                    }
                    fos.close();

                    MediaPlayer mediaPlayer = new MediaPlayer();
                    FileInputStream fis = new FileInputStream(tempVoice);
                    mediaPlayer.setDataSource(fis.getFD());
                    mediaPlayer.prepare();
                    mediaPlayer.start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }).start();
    }
}
