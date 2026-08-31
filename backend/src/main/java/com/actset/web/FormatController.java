package com.actset.web;

import com.actset.format.FormatPreset;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** ⑤ 규격 선택 화면. GET /formats는 DB가 아니라 서버 상수를 직렬화해 내려준다(docs/11). */
@RestController
public class FormatController {

    @GetMapping("/api/v1/formats")
    public Map<String, Object> list() {
        List<Map<String, Object>> items = FormatPreset.all().stream()
                .map(f -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("code", f.code());
                    m.put("label", f.label());
                    m.put("width", f.width());
                    m.put("height", f.height());
                    m.put("group", f.group().name());
                    m.put("ratio_bucket", f.ratioBucket().name());
                    return m;
                })
                .toList();
        return Map.of("items", items);
    }
}
