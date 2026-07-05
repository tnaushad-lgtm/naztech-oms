package com.naztech.oms.controller;

import com.naztech.oms.entity.News;
import com.naztech.oms.repo.NewsRepo;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsRepo newsRepo;

    public NewsController(NewsRepo newsRepo) { this.newsRepo = newsRepo; }

    @GetMapping
    public List<News> list() { return newsRepo.findTop50ByOrderByPublishedAtDesc(); }

    @PostMapping
    public News publish(@RequestBody News n) {
        if (n.getPublishedAt() == null) n.setPublishedAt(LocalDateTime.now());
        if (n.getCategory() == null) n.setCategory("GENERAL");
        return newsRepo.save(n);
    }
}
