package com.ai.developer.service;

import com.ai.developer.config.ToolOutputWebSocketHandler;
import com.ai.developer.llm.ChatContext;
import com.ai.developer.llm.LLMProvider;
import com.ai.developer.llm.Message;
import com.ai.developer.llm.ToolCall;
import com.ai.developer.llm.providers.CustomClaudeLLMProvider.ToolUseBlock;
import com.ai.developer.model.ChatRequest;
import com.ai.developer.model.ChatResponse;
import com.ai.developer.model.SessionResponse;
import com.ai.developer.model.ToolCallResponse;
import com.ai.developer.tools.Tool;
import com.ai.developer.tools.ToolOutput;
import com.ai.developer.tools.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {
    
    private final LLMProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final ToolOutputWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;
    
    // In-memory session storage (would be replaced with database in production)
    private final Map<String, ChatContext> sessions = new ConcurrentHashMap<>();
    
    public Mono<SessionResponse> createSession() {
        String sessionId = UUID.randomUUID().toString();
        log.info("Creating new session: {}", sessionId);
        
        ChatContext context = ChatContext.builder()
                .sessionId(sessionId)
                .messages(new ArrayList<>())
                .build();
        
        // Add system message with comprehensive prompt
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("# AI Developer Agent\n\n");
        systemPrompt.append("You are an expert full-stack developer AI agent designed to assist with software development tasks across the entire software development lifecycle.\n\n");
        
        // Add core identity and expertise
        systemPrompt.append("## Core Identity & Expertise\n\n");
        systemPrompt.append("You are proficient in:\n");
        systemPrompt.append("- **Frontend Development**: Modern JavaScript/TypeScript, React, Vue, Angular, HTML5, CSS3, responsive design, accessibility standards\n");
        systemPrompt.append("- **Backend Development**: Node.js, Python, Java, C#, Go, API design (REST/GraphQL), microservices architecture\n");
        systemPrompt.append("- **Database Management**: SQL and NoSQL databases, query optimization, schema design, migrations\n");
        systemPrompt.append("- **DevOps & Infrastructure**: CI/CD pipelines, containerization (Docker/Kubernetes), cloud platforms (AWS/Azure/GCP)\n");
        systemPrompt.append("- **Security**: Authentication, authorization, input validation, OWASP top 10, secure coding practices\n");
        systemPrompt.append("- **Testing**: Unit testing, integration testing, end-to-end testing, TDD/BDD methodologies\n\n");
        
        // Add development principles
        systemPrompt.append("## Development Principles\n\n");
        systemPrompt.append("### Best Practices\n");
        systemPrompt.append("- **Understand before coding**: Analyze requirements and codebase structure before making changes\n");
        systemPrompt.append("- **Validate before executing**: Review configurations and code before running deployment or destructive operations\n");
        systemPrompt.append("- **Test incrementally**: Use testing tools after each significant change\n");
        systemPrompt.append("- **Document as you go**: Update documentation and comments when modifying code\n\n");
        
        // Add development workflow
        systemPrompt.append("## Development Workflow\n\n");
        systemPrompt.append("### Project Analysis Phase\n");
        systemPrompt.append("1. **Requirements Gathering**: Thoroughly understand the problem, constraints, and success criteria\n");
        systemPrompt.append("2. **Architecture Planning**: Design system architecture, choose appropriate technologies and patterns\n");
        systemPrompt.append("3. **Environment Assessment**: Examine existing codebase, dependencies, and infrastructure\n\n");
        
        systemPrompt.append("### Implementation Phase\n");
        systemPrompt.append("1. **Setup & Configuration**: Establish development environment, dependencies, and build processes\n");
        systemPrompt.append("2. **Iterative Development**: Build features incrementally with regular testing\n");
        systemPrompt.append("3. **Code Review**: Ensure code quality, security, and maintainability standards\n");
        systemPrompt.append("4. **Integration**: Properly integrate new features with existing systems\n\n");
        
        // Add code quality standards
        systemPrompt.append("## Code Quality Standards\n\n");
        systemPrompt.append("### Security First\n");
        systemPrompt.append("- **Input Validation**: Always validate and sanitize user inputs\n");
        systemPrompt.append("- **Authentication**: Implement robust authentication mechanisms\n");
        systemPrompt.append("- **Authorization**: Enforce proper access controls and permissions\n");
        systemPrompt.append("- **Data Protection**: Use encryption for sensitive data, follow GDPR/privacy regulations\n");
        systemPrompt.append("- **Dependency Management**: Keep dependencies updated, scan for vulnerabilities\n\n");
        
        // Add specific tool usage instructions
        systemPrompt.append("## Tool Invocation Instructions\n\n");
        systemPrompt.append("When you need to use a tool:\n");
        systemPrompt.append("1. Clearly state which tool you're using and why\n");
        systemPrompt.append("2. Provide all required parameters in the correct format\n");
        systemPrompt.append("3. Wait for the tool execution results before proceeding\n");
        systemPrompt.append("4. Interpret and explain the tool results to the user\n\n");
        
        systemPrompt.append("You should proactively suggest using tools when they would help solve the user's problem more effectively.");
        
        Message systemMessage = Message.builder()
                .role("system")
                .content(systemPrompt.toString())
                .timestamp(Instant.now())
                .build();
        
        context.getMessages().add(systemMessage);
        log.info("Created new session {} with system message: {}", sessionId, systemMessage.getContent());
        
        sessions.put(sessionId, context);
        
        return Mono.just(SessionResponse.builder()
                .sessionId(sessionId)
                .createdAt(Instant.now())
                .build());
    }
    
    public Mono<List<ChatResponse>> getSessionHistory(String sessionId) {
        ChatContext context = sessions.get(sessionId);
        if (context == null) {
            log.error("Session not found: {}", sessionId);
            return Mono.error(new IllegalArgumentException("Session not found: " + sessionId));
        }
        
        List<ChatResponse> history = context.getMessages().stream()
                .filter(msg -> !"system".equals(msg.getRole())) // Exclude system messages
                .map(msg -> ChatResponse.builder()
                        .sessionId(sessionId)
                        .message(msg.getContent())
                        .role(msg.getRole())
                        .timestamp(msg.getTimestamp())
                        .build())
                .collect(Collectors.toList());
                
        return Mono.just(history);
    }
    
    public Flux<ChatResponse> processMessage(ChatRequest request) {
        String sessionId = request.getSessionId();
        String userMessage = request.getMessage();
        
        log.info("Processing message for session {}: {}", sessionId, userMessage);
        
        ChatContext context = sessions.get(sessionId);
        if (context == null) {
            log.error("Session not found: {}", sessionId);
            return Flux.error(new IllegalArgumentException("Session not found: " + sessionId));
        }
        
        // Log the current context state
        log.info("Current context for session {} has {} messages", sessionId, context.getMessages().size());
        for (int i = 0; i < context.getMessages().size(); i++) {
            Message msg = context.getMessages().get(i);
            log.info("Message {}: role={}, content={}", i, msg.getRole(), 
                    msg.getContent().length() > 50 ? msg.getContent().substring(0, 50) + "..." : msg.getContent());
        }
        
        // Add user message to context
        Message newUserMessage = Message.builder()
                .role("user")
                .content(userMessage)
                .timestamp(Instant.now())
                .build();
        
        context.getMessages().add(newUserMessage);
        log.info("Added user message to context: {}", newUserMessage.getContent());
        
        // Ensure we have at least a system message
        if (context.getMessages().stream().noneMatch(m -> "system".equals(m.getRole()))) {
            log.info("No system message found in context, adding default system message");
            Message systemMessage = Message.builder()
                    .role("system")
                    .content("You are a helpful AI developer assistant. You can help with coding, debugging, and using various development tools.")
                    .timestamp(Instant.now())
                    .build();
            
            context.getMessages().add(0, systemMessage);
            log.info("Added system message to context: {}", systemMessage.getContent());
        }
        
        // Create a defensive copy of the context to prevent message sanitization issues
        ChatContext defensiveCopy = createDefensiveCopy(context);
        
        return llmProvider.streamResponse(userMessage, defensiveCopy)
                .doOnSubscribe(s -> log.info("Starting LLM response stream for session {}", sessionId))
                .doOnComplete(() -> log.info("Completed LLM response stream for session {}", sessionId))
                .doOnError(e -> log.error("Error in LLM response stream for session {}: {}", sessionId, e.getMessage()))
                .flatMap(chunk -> {
                    // Check if this chunk contains a tool use request
                    if (chunk.startsWith("[TOOL_USE:")) {
                        log.info("Tool use detected in response for session {}", sessionId);
                        
                        try {
                            // Extract the tool use JSON from the marker
                            String toolUseJson = chunk.substring(10, chunk.length() - 1);
                            log.info("Extracted tool use JSON: {}", toolUseJson);
                            
                            // Parse the tool use block
                            ToolUseBlock toolUseBlock = objectMapper.readValue(toolUseJson, ToolUseBlock.class);
                            
                            // Handle the tool use request
                            return handleToolUse(sessionId, toolUseBlock)
                                    .map(result -> {
                                        // Return a response indicating tool execution
                                        return ChatResponse.builder()
                                                .sessionId(sessionId)
                                                .message("[Tool execution completed: " + toolUseBlock.getName() + "]")
                                                .role("assistant")
                                                .toolCallId(toolUseBlock.getId())
                                                .toolCall(ToolCallResponse.builder()
                                                        .name(toolUseBlock.getName())
                                                        .result(result)
                                                        .build())
                                                .timestamp(Instant.now())
                                                .build();
                                    });
                        } catch (JsonProcessingException e) {
                            log.error("Error parsing tool use JSON: {}", e.getMessage());
                            return Mono.just(ChatResponse.builder()
                                    .sessionId(sessionId)
                                    .message("[Error executing tool: " + e.getMessage() + "]")
                                    .role("assistant")
                                    .timestamp(Instant.now())
                                    .build());
                        }
                    }
                    
                    // For the first chunk, add the assistant message to the context
                    if (context.getMessages().stream().noneMatch(m -> 
                            "assistant".equals(m.getRole()) && 
                            m.getTimestamp() != null && 
                            m.getTimestamp().isAfter(Instant.now().minusSeconds(5)))) {
                        
                        Message assistantMessage = Message.builder()
                                .role("assistant")
                                .content("")
                                .timestamp(Instant.now())
                                .build();
                        
                        context.getMessages().add(assistantMessage);
                        log.info("Added initial assistant message to context for session {}", sessionId);
                    }
                    
                    // Update the latest assistant message with the new chunk
                    Message latestAssistantMessage = context.getMessages().stream()
                            .filter(m -> "assistant".equals(m.getRole()))
                            .reduce((first, second) -> second)
                            .orElse(null);
                    
                    if (latestAssistantMessage != null) {
                        latestAssistantMessage.setContent(latestAssistantMessage.getContent() + chunk);
                    }
                    
                    return Mono.just(ChatResponse.builder()
                            .sessionId(sessionId)
                            .message(chunk)
                            .role("assistant")
                            .timestamp(Instant.now())
                            .build());
                });
    }
    
    /**
     * Handle a tool use request from the LLM
     */
    private Mono<String> handleToolUse(String sessionId, ToolUseBlock toolUseBlock) {
        log.info("Handling tool use for session {}: tool={}, input={}", 
                sessionId, toolUseBlock.getName(), toolUseBlock.getInput());
        
        ChatContext context = sessions.get(sessionId);
        if (context == null) {
            log.error("Session not found: {}", sessionId);
            return Mono.error(new IllegalArgumentException("Session not found: " + sessionId));
        }
        
        Tool tool = toolRegistry.getTool(toolUseBlock.getName());
        if (tool == null) {
            log.error("Tool not found: {}", toolUseBlock.getName());
            return Mono.error(new IllegalArgumentException("Tool not found: " + toolUseBlock.getName()));
        }
        
        // Create a tool call message to add to the context
        String toolCallId = toolUseBlock.getId();
        
        try {
            String argumentsJson = objectMapper.writeValueAsString(toolUseBlock.getInput());
            
            // Add tool call message to context
            Message toolCallMessage = Message.builder()
                    .role("assistant")
                    .toolCallId(toolCallId)
                    .toolCall(ToolCall.builder()
                            .id(toolCallId)
                            .name(toolUseBlock.getName())
                            .arguments(argumentsJson)
                            .build())
                    .timestamp(Instant.now())
                    .build();
                    
            context.getMessages().add(toolCallMessage);
            log.info("Added tool call message to context for tool: {}", toolUseBlock.getName());
            
            // Execute the tool and collect results
            return executeToolCall(sessionId, toolUseBlock.getName(), toolUseBlock.getInput())
                    .collectList()
                    .map(outputs -> {
                        // Combine all outputs into a single string
                        StringBuilder result = new StringBuilder();
                        for (ToolOutput output : outputs) {
                            result.append(output.getContent()).append("\n");
                        }
                        
                        // Add tool result message to context
                        Message toolResultMessage = Message.builder()
                                .role("tool")
                                .toolCallId(toolCallId)
                                .content(result.toString())
                                .timestamp(Instant.now())
                                .build();
                                
                        context.getMessages().add(toolResultMessage);
                        log.info("Added tool result message to context for tool: {}", toolUseBlock.getName());
                        
                        return result.toString();
                    });
        } catch (JsonProcessingException e) {
            log.error("Error serializing tool arguments: {}", e.getMessage());
            return Mono.just("Error executing tool: " + e.getMessage());
        }
    }
    
    /**
     * Execute a tool call and return the results
     */
    public Flux<ToolOutput> executeToolCall(String sessionId, String toolName, Map<String, Object> arguments) {
        log.info("Executing tool {} for session {} with arguments: {}", toolName, sessionId, arguments);
        
        ChatContext context = sessions.get(sessionId);
        if (context == null) {
            log.error("Session not found: {}", sessionId);
            return Flux.error(new IllegalArgumentException("Session not found: " + sessionId));
        }
        
        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            log.error("Tool not found: {}", toolName);
            return Flux.error(new IllegalArgumentException("Tool not found: " + toolName));
        }
        
        // Create a tool call message to add to the context
        String toolCallId = UUID.randomUUID().toString();
        
        try {
            // Execute the tool and collect results
            return toolRegistry.executeTool(toolName, arguments)
                    .doOnNext(output -> {
                        // Send tool output to WebSocket
                        try {
                            Map<String, Object> toolOutput = new HashMap<>();
                            toolOutput.put("sessionId", sessionId);
                            toolOutput.put("toolName", toolName);
                            toolOutput.put("toolCallId", toolCallId);
                            toolOutput.put("args", arguments);
                            toolOutput.put("output", output);
                            toolOutput.put("timestamp", Instant.now());
                            
                            webSocketHandler.broadcastToolOutput(toolOutput);
                            log.info("Broadcasted tool output for tool: {}", toolName);
                        } catch (Exception e) {
                            log.error("Error broadcasting tool output: {}", e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("Error executing tool: {}", e.getMessage());
            return Flux.error(e);
        }
    }
    
    private ChatContext createDefensiveCopy(ChatContext original) {
        List<Message> copiedMessages = new ArrayList<>();
        
        for (Message msg : original.getMessages()) {
            Message copiedMsg = Message.builder()
                    .role(msg.getRole())
                    .content(msg.getContent())
                    .timestamp(msg.getTimestamp())
                    .build();
                    
            if (msg.getToolCallId() != null) {
                copiedMsg.setToolCallId(msg.getToolCallId());
            }
            
            if (msg.getToolCall() != null) {
                copiedMsg.setToolCall(ToolCall.builder()
                        .id(msg.getToolCall().getId())
                        .name(msg.getToolCall().getName())
                        .arguments(msg.getToolCall().getArguments())
                        .build());
            }
            
            copiedMessages.add(copiedMsg);
        }
        
        return ChatContext.builder()
                .sessionId(original.getSessionId())
                .messages(copiedMessages)
                .build();
    }
}
