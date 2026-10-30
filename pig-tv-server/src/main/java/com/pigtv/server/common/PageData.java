package com.pigtv.server.common;

import java.util.List;

public record PageData<T>(List<T> items, long total, int page, int size) {
}
