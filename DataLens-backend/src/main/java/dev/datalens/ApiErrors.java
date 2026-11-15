package dev.datalens;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.Map;

@RestControllerAdvice
class ApiErrors {
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> status(ResponseStatusException error){return ResponseEntity.status(error.getStatus()).body(Map.of("message",error.getReason()==null?"请求失败":error.getReason()));}
 @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> size(){return ResponseEntity.status(413).body(Map.of("message","文件超过 20 MB 上传限制"));}
 @ExceptionHandler({java.io.IOException.class,IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) ResponseEntity<?> input(Exception error){return ResponseEntity.badRequest().body(Map.of("message","输入或文件格式无效，请检查解析选项；未跳过错误数据"));}
}
