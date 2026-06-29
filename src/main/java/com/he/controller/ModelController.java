package com.he.controller;

import com.he.service.SpringAiModelRouterService;
import com.he.service.SpringAiModelRouterService.ModelInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final SpringAiModelRouterService modelRouter;

    public ModelController(SpringAiModelRouterService modelRouter) {
        this.modelRouter = modelRouter;
    }

    @GetMapping
    public List<ModelInfo> list() {
        return modelRouter.getAvailableModels();
    }
}

