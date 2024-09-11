package com.thehecklers.ai202;

import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.model.Media;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.audio.speech.SpeechPrompt;
import org.springframework.ai.openai.audio.speech.SpeechResponse;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

@RestController
public class Ai202Controller {
    private final ChatClient client;
    private final ChatModel chatModel;
    private final OpenAiAudioSpeechModel speechModel;
    private final VectorStore vectorStore;
    private final ImageModel imageModel;

    public Ai202Controller(ChatClient.Builder builder, ChatModel chatModel, OpenAiAudioSpeechModel speechModel, VectorStore vectorStore, ImageModel imageModel) {
        // We'll revisit this later. This is going to be legen...wait for it...
        //this.client = builder.build(); ...DARY!
        this.client = builder.defaultAdvisors(
                        new MessageChatMemoryAdvisor(new InMemoryChatMemory(), "default", 10))
                .build();

        this.chatModel = chatModel;
        this.speechModel = speechModel;
        this.vectorStore = vectorStore;
        this.imageModel = imageModel;
    }

    @GetMapping
    public String getString(@RequestParam(defaultValue = "What is the meaning of life?") String message) {
        return client.prompt()
                .user(message)
                .call()
                .content();
    }

    @GetMapping("/response")
    public ChatResponse getResponse(@RequestParam(defaultValue = "What is the meaning of life?") String message) {
        return client.prompt()
                .user(message)
                .call()
                .chatResponse();
    }

    @GetMapping("/template")
    public String getTemplateResponse(@RequestParam String type,
                                      @RequestParam String topic) {
        /*// Can do this a bit differently, dealer's choice
        var template = new PromptTemplate("Write a {type} about {topic}.",
                Map.of("type", type, "topic", topic));
        return client.prompt(template.create()).call().content();*/

        return client.prompt()
                .user(u -> u.text("Write a {type} about {topic}.")
                        .param("type", type).param("topic", topic))
                .call()
                .content();
    }

    @GetMapping("/rag")
    public String getRagResponseFromOurData(@RequestParam(defaultValue = "Airspeeds") String message) {
        return client.prompt()
                .user(message)
                .advisors(new QuestionAnswerAdvisor(vectorStore, SearchRequest.defaults()))
                .call()
                .content();
    }

    // Insert stateless joke here!
    @GetMapping("/conversation")
    public ChatResponse getConversation(@RequestParam(defaultValue = "What is the meaning of life?") String message,
                                        @RequestParam(defaultValue = "default") String conversationId) {
        return client.prompt()
                .user(message)
                .advisors(as -> as.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                .call()
                .chatResponse();
    }

    @GetMapping("/translate")
    public String getTranslation(@RequestParam(defaultValue = "What is the meaning of life?") String message,
                                 @RequestParam(required = false) String language,
                                 @RequestParam(defaultValue = "false") boolean save) throws IOException {
        var content = client.prompt()
                .user(message)
                .system(s -> {
                    if (language != null) s.text("You respond in {language}")
                            .param("language", language);
                })
                .call()
                .content();

        if (save) convertToSpeech(content, "/Users/markheckler/files/TTS_Output.mp3");

        return content;
    }

    @GetMapping("/docaudio")
    public String convertDocToAudio(@RequestParam String filepath) throws IOException {
        /*
         Using GET and not POST since we're feeding in a file path or URL, then using that
         to navigate to the file and read it in. No request body needed or even desired.
         */
        var logger = LoggerFactory.getLogger(Ai202Controller.class);
        var importFile = filepath.startsWith("http")
                ? new UrlResource(filepath)
                : new FileSystemResource(filepath);
        var infile = importFile.getFilename() != null
                ? importFile.getFilename().substring(0, importFile.getFilename().lastIndexOf('.'))
                : "DocAudio";
        var outfile = String.format("/Users/markheckler/files/%s.mp3", infile);
        var docBuilder = new StringBuilder();
        var tikaDocumentReader = new TikaDocumentReader(importFile);

        logger.info("Processing " + importFile.getFilename());

        tikaDocumentReader.get()
                .stream()
                .forEach(doc -> docBuilder.append(doc.getContent()));

        var docContent = docBuilder.toString();

        logger.info("Converting to audio and saving " + outfile);

        convertToSpeech(docContent, outfile);

        logger.info("Audio conversion and save complete for " + outfile);

        return docContent;
    }

    private void convertToSpeech(String content, String outputDest) throws IOException {
        var fsr = new FileSystemResource(outputDest);

        SpeechResponse response = speechModel.call(new SpeechPrompt(content));

        fsr.getOutputStream().write(response.getResult().getOutput());
    }

    // Multimodal magic! (Insert "Any sufficiently advanced technology is indistinguishable from magic" quote here)
    @GetMapping("/mm")
    public String getImageDescription(@RequestParam(defaultValue = "/Users/markheckler/files/testimage.jpg") String imagePath) throws MalformedURLException {
        // For sample URL, try this (courtesy of Spring AI docs): "https://docs.spring.io/spring-ai/reference/1.0-SNAPSHOT/_images/multimodal.test.png"
        // For sample local file, you'll need to supply a full filepath
        // Keep it simple, only accept JPEGs and PNGs
        var imageType = imagePath.endsWith(".jpg") ? MimeTypeUtils.IMAGE_JPEG : MimeTypeUtils.IMAGE_PNG;
        var media = (imagePath.startsWith("http") ?
                new Media(imageType, new URL(imagePath)) :
                new Media(imageType, new FileSystemResource(imagePath)));
        var userMessage = new UserMessage("What is this image?", media);

        return chatModel.call(new Prompt(userMessage,
                        OpenAiChatOptions.builder().withModel(OpenAiApi.ChatModel.GPT_4_O.getValue()).build()))
                .getResult()
                .getOutput()
                .getContent();
    }

    @GetMapping("/image")
    public ImageResponse createImage(@RequestParam(defaultValue = "Two dogs playing chess") String description) {
        return imageModel.call(new ImagePrompt(description));
    }
}
