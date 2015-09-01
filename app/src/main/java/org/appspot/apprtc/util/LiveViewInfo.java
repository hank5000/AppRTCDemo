package org.appspot.apprtc.util;

/**
 * Created by HankWu_Office on 2015/8/26.
 */
public class LiveViewInfo {
    int numberOfLiveView;
    String[] ip_address = new String[8];
    String[] nick_name  = new String[8];
    int[]    onChannel  = new int[8];

    public LiveViewInfo() {
        numberOfLiveView = 0;
        for(int i=0;i<8;i++) {
            ip_address[i] = "";
            nick_name[i] = "";
            onChannel[i] = -1;
        }
    }

    public void reset() {
        numberOfLiveView = 0;
        for(int i=0;i<8;i++) {
            ip_address[i] = "";
            nick_name[i] = "";
            onChannel[i] = -1;
        }
    }

    public int getNumberOfLiveView() {
        return numberOfLiveView;
    }

    public void add(String ip,String nickname) {
        for(int i=0;i<8;i++) {
            if(ip_address[i].equalsIgnoreCase("")) {
                ip_address[i] = ip;
                nick_name[i] = nickname;
                numberOfLiveView++;
                break;
            }
        }
    }

    public void add(String nickname) {
        for(int i=0;i<8;i++) {
            if(nick_name[i].equalsIgnoreCase("")) {
                nick_name[i] = nickname;
                numberOfLiveView++;
                break;
            }
        }
    }

    public void setNickNameOnChannel(String nickName, int channel_index) {
        for(int i=0;i<8;i++) {
            if(nick_name[i].equalsIgnoreCase(nickName)) {
                onChannel[i] = channel_index;
            }
        }
    }

    public int getOnChannel(String nickName) {
        for(int i=0;i<8;i++) {
            if(nick_name[i].equalsIgnoreCase(nickName)) {
                return onChannel[i];
            }
        }
        return -2;
    }

    public void removeNickNameOnChannel(String nickName) {
        for(int i=0;i<8;i++) {
            if(nick_name[i].equalsIgnoreCase(nickName)) {
                onChannel[i] = -1;
                break;
            }
        }
    }

    public void removeOnChannel(int channel_index) {
        for(int i=0;i<8;i++) {
            if(onChannel[i] == channel_index) {
                onChannel[i] = -1;
                break;
            }
        }
    }

    public void remove(String ip_or_nickname) {
        for(int i=0;i<8;i++) {
            if(ip_address[i].equalsIgnoreCase(ip_or_nickname) || nick_name[i].equalsIgnoreCase(ip_or_nickname)) {
                ip_address[i] = "";
                nick_name[i] = "";
                onChannel[i] = -1;
                numberOfLiveView--;
                break;
            }
        }
    }

    public String getNickNameByIndex(int Index) {
        return nick_name[Index];
    }

    public String getIpAddress(String nickName) {
        String ip = null;
        for(int i=0;i<8;i++) {
            if(nick_name[i].equalsIgnoreCase(nickName)) {
                ip = ip_address[i];
                break;
            }
        }
        return ip;
    }
}
