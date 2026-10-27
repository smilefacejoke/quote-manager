package com.quote.manager.service;

import com.quote.manager.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 数据库一键备份：H2 在线 SCRIPT 导出，不中断服务。
 * 输出目录 backups/，文件名 quote-db_yyyyMMdd_HHmmss.zip
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private static final String BACKUP_DIR = "backups";

    private final DataSource dataSource;

    /** 执行备份，返回相对文件名 */
    public String backup() {
        try {
            Path dir = Paths.get(BACKUP_DIR).toAbsolutePath();
            Files.createDirectories(dir);
            String name = "quote-db_" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip";
            Path file = dir.resolve(name);
            // H2 BACKUP TO：运行中导出标准 zip 备份，可再用 BACKUP 恢复/校验
            String target = file.toString().replace("\\", "/");
            try (Connection conn = dataSource.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("BACKUP TO '" + target + "'");
            }
            if (!Files.exists(file) || Files.size(file) == 0) {
                throw new BizException("备份文件未生成，请检查 backups/ 目录权限");
            }
            log.info("数据库已备份 {} ({} bytes)", file, Files.size(file));
            return name;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new BizException("备份失败：" + msg);
        }
    }
}
