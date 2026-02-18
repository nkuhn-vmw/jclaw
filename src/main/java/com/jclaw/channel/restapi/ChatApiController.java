package com.jclaw.channel.restapi;

import com.jclaw.agent.AgentContext;
import com.jclaw.agent.AgentResponse;
import com.jclaw.agent.AgentRuntime;
import com.jclaw.channel.InboundMessage;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    private final AgentRuntime agentRuntime;

    public ChatApiController(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @PostMapping("/send")
    public Mono<ChatResponse> sendMessage(@RequestBody ChatRequest request,
                                          Authentication auth) {
        String principal = auth.getName();
        String agentId = request.agentId() != null ? request.agentId() : "default";

        InboundMessage message = new InboundMessage(
                "rest-api", principal, request.conversationId(), request.message());

        AgentContext context = new AgentContext(agentId, principal, "rest-api");

        return agentRuntime.processMessage(context, message)
                .collectList()
                .map(responses -> {
                    String combined = responses.stream()
                            .map(AgentResponse::content)
                            .collect(java.util.stream.Collectors.joining(""));
                    return new ChatResponse(combined, agentId);
                });
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentResponse> streamMessage(@RequestBody ChatRequest request,
                                              Authentication auth) {
        String principal = auth.getName();
        String agentId = request.agentId() != null ? request.agentId() : "default";

        InboundMessage message = new InboundMessage(
                "rest-api", principal, request.conversationId(), request.message());

        AgentContext context = new AgentContext(agentId, principal, "rest-api");

        return agentRuntime.processMessage(context, message);
    }

    public record ChatRequest(
            String message,
            String agentId,
            String conversationId
    ) {}

    public record ChatResponse(
            String response,
            String agentId
    ) {}
}
