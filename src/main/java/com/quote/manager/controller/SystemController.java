package com.quote.manager.controller;

import com.quote.manager.common.ApiResponse;
import com.quote.manager.service.BackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 系统维护：数据库备份 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final BackupService backupService;

    /**
     * 一键备份数据库到 backups/（H2 在线导出，服务不用停）。
     * POST /api/system/backup
     */
    @PostMapping("/backup")
    public ApiResponse<Map<String, Object>> backup() {
        String file = backupService.backup();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file", "backups/" + file);
        return ApiResponse.ok(body);
    }
}
