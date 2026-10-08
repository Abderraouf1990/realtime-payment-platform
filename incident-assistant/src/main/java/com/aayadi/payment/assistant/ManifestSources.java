package com.aayadi.payment.assistant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reads only a deployment-controlled manifest. Construction itself performs no file access. */
public final class ManifestSources implements ContextBuilder.SourceReader {
    private final Path root;
    public ManifestSources(Path repositoryRoot) { this.root = repositoryRoot.toAbsolutePath().normalize(); }
    private record Candidate(ContextBuilder.Excerpt excerpt, int score) {}

    @Override public List<ContextBuilder.Excerpt> retrieve(Set<String> terms) {
        return retrieve(terms, Set.of());
    }

    /** Optional source restriction is checked against the manifest; it is never a filesystem path. */
    public List<ContextBuilder.Excerpt> retrieve(Set<String> terms, Set<String> restrictedIds) {
        try {
            Path realRoot = root.toRealPath();
            var manifest = JsonMapper.builder().build().readTree(read(realRoot,
                    "docs/assistant/v1/sources.json", 32768));
            keys(manifest, Set.of("version", "normalization", "sources"));
            if (!manifest.path("version").asString().equals("1") || !manifest.get("sources").isArray()
                    || manifest.get("sources").size() < 1 || manifest.get("sources").size() > 32)
                throw new IllegalArgumentException();
            var ids = new HashSet<String>();
            var candidates = new ArrayList<Candidate>();
            for (var source : manifest.get("sources")) {
                keys(source, Set.of("id", "path", "sha256"));
                String id = source.get("id").asString(), path = source.get("path").asString();
                String hash = source.get("sha256").asString();
                if (!id.matches("[a-z][a-z0-9-]{0,63}") || !ids.add(id)
                        || !path.matches("docs/(adr|runbooks)/[a-z0-9-]+\\.md") || !hash.matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException();
                String document = read(realRoot, path, 65536).replace("\r\n", "\n");
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(document.getBytes(StandardCharsets.UTF_8)));
                if (!actual.equals(hash)) throw new IllegalArgumentException();
                // Verify every manifest entry, including sources not selected for this query.
                if (!restrictedIds.isEmpty() && !restrictedIds.contains(id)) continue;
                var lines = document.split("\n", -1);
                Candidate best = null;
                for (int start = 0; start < lines.length;) {
                    int end = start; var chunk = new StringBuilder();
                    while (end < lines.length && end - start < 12 && chunk.length() + lines[end].length() + 1 <= 1800) {
                        chunk.append(lines[end]).append('\n'); end++;
                    }
                    if (end == start) { start++; continue; } // Never split an oversized line into a misleading quote.
                    String text = chunk.toString();
                    var matching = new HashSet<>(EvidenceFilter.terms(text)); matching.retainAll(terms);
                    int score = matching.size();
                    var excerpt = new ContextBuilder.Excerpt(id, path, hash, start + 1, end, text, "UNTRUSTED_DOCUMENT_DATA");
                    if (score > 0 && (best == null || score > best.score())) best = new Candidate(excerpt, score);
                    start = end;
                }
                if (best != null) candidates.add(best);
            }
            if (!ids.containsAll(restrictedIds)) throw new IllegalArgumentException();
            return candidates.stream().sorted(Comparator.comparingInt(Candidate::score).reversed()
                            .thenComparing(c -> c.excerpt().sourceId()))
                    .limit(6).map(Candidate::excerpt).toList();
        } catch (Exception e) {
            // Do not retain exception causes containing paths or source content.
            throw new IllegalStateException("INVALID_SOURCES");
        }
    }

    private static void keys(JsonNode node, Set<String> expected) {
        if (!node.isObject()) throw new IllegalArgumentException();
        var actual = new HashSet<String>();
        for (var entry : node.properties()) actual.add(entry.getKey());
        if (!actual.equals(expected)) throw new IllegalArgumentException();
    }
    private static String read(Path realRoot, String relative, int limit) throws Exception {
        Path candidate = realRoot.resolve(relative).normalize();
        Path real = candidate.toRealPath();
        if (!real.startsWith(realRoot.resolve("docs")) || !Files.isRegularFile(real)) throw new IllegalArgumentException();
        // Reject symlinks/junctions even when they resolve within docs.
        for (Path part = candidate; !part.equals(realRoot); part = part.getParent())
            if (Files.isSymbolicLink(part) || !part.toRealPath().equals(part)) throw new IllegalArgumentException();
        try (var input = Files.newInputStream(real)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IllegalArgumentException();
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }
}
