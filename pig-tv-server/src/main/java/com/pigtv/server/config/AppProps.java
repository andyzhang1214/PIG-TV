package com.pigtv.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pigtv")
public class AppProps {

    private Jwt jwt = new Jwt();
    private Storage storage = new Storage();
    private Risk risk = new Risk();
    private Admin admin = new Admin();

    public static class Jwt {
        private String secret = "pig-tv-server-please-change-this-secret-in-production-2026";
        private int ttlHours = 720;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(int ttlHours) {
            this.ttlHours = ttlHours;
        }
    }

    public static class Storage {
        private String dir = "./data/apk";
        private String baseUrl = "http://127.0.0.1:8088/files";

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Risk {
        private int maxSegments = 3;
        private int banHours = 72;
        private int sameDeviceGraceMinutes = 60;
        private int onlineWindowMinutes = 5;
        private int unbanCooldownHours = 24;

        public int getMaxSegments() {
            return maxSegments;
        }

        public void setMaxSegments(int maxSegments) {
            this.maxSegments = maxSegments;
        }

        public int getBanHours() {
            return banHours;
        }

        public void setBanHours(int banHours) {
            this.banHours = banHours;
        }

        public int getSameDeviceGraceMinutes() {
            return sameDeviceGraceMinutes;
        }

        public void setSameDeviceGraceMinutes(int v) {
            this.sameDeviceGraceMinutes = v;
        }

        public int getOnlineWindowMinutes() {
            return onlineWindowMinutes;
        }

        public void setOnlineWindowMinutes(int v) {
            this.onlineWindowMinutes = v;
        }

        public int getUnbanCooldownHours() {
            return unbanCooldownHours;
        }

        public void setUnbanCooldownHours(int v) {
            this.unbanCooldownHours = v;
        }
    }

    public static class Admin {
        private String initUsername = "admin";
        private String initPassword = "admin123";

        public String getInitUsername() {
            return initUsername;
        }

        public void setInitUsername(String v) {
            this.initUsername = v;
        }

        public String getInitPassword() {
            return initPassword;
        }

        public void setInitPassword(String v) {
            this.initPassword = v;
        }
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Risk getRisk() {
        return risk;
    }

    public void setRisk(Risk risk) {
        this.risk = risk;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }
}
