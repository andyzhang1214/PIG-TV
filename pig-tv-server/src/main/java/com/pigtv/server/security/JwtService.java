package com.pigtv.server.security;

import com.pigtv.server.config.AppProps;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final int ttlHours;

    public JwtService(AppProps props) {
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.ttlHours = props.getJwt().getTtlHours();
    }

    public String newJti() {
        return UUID.randomUUID().toString();
    }

    public String issue(String typ, long id, String name, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(id))
                .id(jti)
                .claim("typ", typ)
                .claim("name", name)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /** 解析失败（过期/篡改）会抛 JwtException，由调用方转 401 */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
