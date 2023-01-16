package com.example.Amadeus;


import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;

import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Voice_reply {
    private Handler myHandler;
    public static String last_voice;
    public InputStream voice_byte = null;
    public static String filepath = Environment.getExternalStorageDirectory().getPath() + "/Music/Amadeus";


    Voice_reply(Handler myHandler){
        this.myHandler = myHandler;
    }
    void get_voice(String input,Handler myHandler) {
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
                    voice_byte = response.body().byteStream();
                    save_voice();
                    Message msg = Message.obtain();
                    msg.what = 1;
                    myHandler.sendMessage(msg);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }).start();
    }

    public void voice_play(String input,Handler myHandler) throws IOException {
        if(Objects.equals(input, "last")){
            input = last_voice;
        }
        if(find_mp3(input)){
            // 表明无需等待网络请求
            Message msg = Message.obtain();
            msg.what = 2;
            myHandler.sendMessage(msg);
            //播放音频
            MediaPlayer mediaPlayer = new MediaPlayer();
            String file;
            file = filepath +"/"+ md5(input) + ".mp3";
            mediaPlayer.setDataSource(file);
            mediaPlayer.prepare();
            mediaPlayer.start();
        }else{
            last_voice = input;
            get_voice(input,myHandler);
        }
    }

    public void save_voice() throws IOException {
        File voice_file = new File(filepath+"/"+md5(last_voice)+".mp3");
        FileOutputStream fos = new FileOutputStream(voice_file);

        int byteRead;
        while((byteRead=voice_byte.read()) != -1){
            fos.write(byteRead);
        }
        fos.close();
    }

    public boolean find_mp3(String input){
//        String path = Environment.getExternalStorageDirectory().getPath() + "/Music/Amadeus";
        File file=new File(filepath);
        //文件夹不存在，则创建它
        if(!file.exists()){
            file.mkdir();
        }
        File[] subFile = file.listFiles();

        for (File value : subFile) {
            // 判断是否为文件夹
            if (!value.isDirectory()) {
                String filename = value.getName();
                if(filename.equals(md5(input)+".mp3"))
                    return true;
            }
        }
        return false;
    }

    // 对字符串进行md5加密
    public String md5(String src){
        try {
            MessageDigest m = MessageDigest.getInstance("MD5");
            m.update(src.getBytes(StandardCharsets.UTF_8));
            byte[] s = m.digest();
            StringBuilder srcBuilder = new StringBuilder();
            for (byte b : s) {
                srcBuilder.append(Integer.toHexString((0x000000ff & b) | 0xffffff00).substring(6));
            }
            src = srcBuilder.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return src;
    }
}
