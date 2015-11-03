package com.via.rtc.util;

import android.util.Log;

/**
 * Created by HankWu_Office on 2015/10/2.
 */
public class GlobalSetting {
    public static class member {
        private final static boolean m_bForSalesMode = true;
        private final static String  m_applicationPrefix = "VIA-RTC";
        private final static boolean m_bPrintIOMessage = false;
        private final static String TAG = m_applicationPrefix + " DEBUG";
        private final static int m_dataChannelDividedSize = 1024;

        public static String getApplicationPrefix() {
            return m_applicationPrefix;
        }

        public static boolean isForSalesMode() {
            return m_bForSalesMode;
        }

        public static int getDataChannelDividedSize() {
            return m_dataChannelDividedSize;
        }

        public static boolean isPrintIOMessageMode() {
            return m_bPrintIOMessage;
        }

        public static void LOGD(String mesg) {
            if(m_bPrintIOMessage) {
                Log.d(TAG, mesg);
            }
        }
    }



}
