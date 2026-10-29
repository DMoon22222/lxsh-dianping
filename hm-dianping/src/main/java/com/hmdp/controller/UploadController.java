package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {
    @Value("${hmdp.image-upload-dir:${HMDP_IMAGE_UPLOAD_DIR:}}")
    private String imageUploadDir;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        if (image.isEmpty()) {
            return Result.fail("请选择图片文件");
        }
        if (image.getSize() > 5L * 1024 * 1024) {
            return Result.fail("图片不能超过5MB");
        }
        String suffix = StrUtil.subAfter(image.getOriginalFilename(), ".", true);
        if (suffix == null || !Arrays.asList("jpg", "jpeg", "png", "gif", "webp")
                .contains(suffix.toLowerCase(Locale.ROOT))) {
            return Result.fail("仅支持JPG、PNG、GIF、WebP图片");
        }
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件到nginx静态资源目录
            File targetFile = new File(
                    imageRoot(),
                    trimLeadingSlash(fileName)
            );
            File parentFile = targetFile.getParentFile();
            if (parentFile != null && !parentFile.exists()) {
                parentFile.mkdirs();
            }
            image.transferTo(targetFile);
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        String relativeName = trimLeadingSlash(filename);
        if (relativeName.startsWith("imgs/")) {
            relativeName = relativeName.substring("imgs/".length());
        }
        Path root = imageRoot().toPath().normalize();
        Path target = root.resolve(relativeName.replace('\\', '/')).normalize();
        if (!relativeName.replace('\\', '/').startsWith("blogs/") || !target.startsWith(root.resolve("blogs"))) {
            return Result.fail("错误的文件名称");
        }
        File file = target.toFile();
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        String relativeDir = StrUtil.format("blogs/{}/{}", d1, d2);
        // 判断目录是否存在
        File dir = new File(imageRoot(), relativeDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/{}/{}.{}", relativeDir, name, suffix);
    }

    private String trimLeadingSlash(String path) {
        if (path == null) {
            return "";
        }
        while (path.startsWith("/") || path.startsWith("\\")) {
            path = path.substring(1);
        }
        return path;
    }

    private File imageRoot() {
        return new File(StrUtil.isBlank(imageUploadDir) ? SystemConstants.IMAGE_UPLOAD_DIR : imageUploadDir)
                .getAbsoluteFile();
    }
}
