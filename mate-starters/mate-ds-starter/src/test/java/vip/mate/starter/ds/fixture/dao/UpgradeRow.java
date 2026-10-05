package vip.mate.starter.ds.fixture.dao;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mate_upgrade_test")
public class UpgradeRow {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String name;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;
}
