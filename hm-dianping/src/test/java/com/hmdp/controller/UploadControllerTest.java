package com.hmdp.controller;

import com.hmdp.dto.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UploadControllerTest {
    @TempDir Path root;
    private UploadController controller;

    @BeforeEach
    void setUp() {
        controller = new UploadController();
        ReflectionTestUtils.setField(controller, "imageUploadDir", root.toString());
    }

    @Test
    void configuredDirectoryIsUsedForUploadAndPrefixedDelete() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        Result result = controller.uploadImage(new MockMultipartFile("file", "photo.PNG", "image/png", bytes));
        assertTrue(result.getSuccess());
        String relative = (String) result.getData();
        assertTrue(relative.startsWith("/blogs/"));
        Path stored = root.resolve(relative.substring(1));
        assertArrayEquals(bytes, Files.readAllBytes(stored));
        assertTrue(controller.deleteBlogImg("/imgs" + relative).getSuccess());
        assertFalse(Files.exists(stored));
    }

    @Test
    void invalidUploadsCreateNoFiles() throws Exception {
        assertFalse(controller.uploadImage(new MockMultipartFile("file", "empty.png", "image/png", new byte[0])).getSuccess());
        assertFalse(controller.uploadImage(new MockMultipartFile("file", "script.html", "text/html", new byte[]{1})).getSuccess());
        assertFalse(controller.uploadImage(new MockMultipartFile("file", "large.jpg", "image/jpeg", new byte[5*1024*1024+1])).getSuccess());
        try (java.util.stream.Stream<Path> files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void deleteCannotTraverseOutsideConfiguredImageRoot() throws Exception {
        Path preserved = root.resolve("preserved.txt");
        Files.write(preserved, new byte[]{1});
        assertFalse(controller.deleteBlogImg("blogs/../preserved.txt").getSuccess());
        assertFalse(controller.deleteBlogImg("blogs/../../outside.txt").getSuccess());
        assertFalse(controller.deleteBlogImg("../outside.txt").getSuccess());
        assertTrue(Files.exists(preserved));
    }
}
