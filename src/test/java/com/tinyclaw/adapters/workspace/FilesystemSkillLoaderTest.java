package com.tinyclaw.adapters.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemSkillLoaderTest {

    @TempDir
    Path tempDir;

    private final FilesystemSkillLoader loader = new FilesystemSkillLoader();

    @Test
    void loadSkillsFromEmptyDirectoryReturnsEmpty() {
        Optional<String> result = loader.loadSkills(tempDir.toString());
        assertThat(result).isEmpty();
    }

    @Test
    void loadSkillsFromMissingDirectoryReturnsEmpty() {
        Optional<String> result = loader.loadSkills(tempDir.resolve("nonexistent").toString());
        assertThat(result).isEmpty();
    }

    @Test
    void loadSkillsWithValidSkillFiles() throws IOException {
        Path skillsDir = tempDir.resolve(".claw/skills");
        Path skillDir = skillsDir.resolve("test-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: Test Skill
            description: A test skill for unit testing
            ---
            This is the body of the test skill.
            It can contain multiple lines.
            """);

        Optional<String> result = loader.loadSkills(tempDir.toString());

        assertThat(result).isPresent();
        String skills = result.get();
        assertThat(skills).contains("Test Skill");
        assertThat(skills).contains("A test skill for unit testing");
        assertThat(skills).contains("This is the body of the test skill");
    }

    @Test
    void loadSkillsWithMultipleSkills() throws IOException {
        Path skillsDir = tempDir.resolve(".claw/skills");
        Path skill1Dir = skillsDir.resolve("skill-one");
        Path skill2Dir = skillsDir.resolve("skill-two");
        Files.createDirectories(skill1Dir);
        Files.createDirectories(skill2Dir);
        Files.writeString(skill1Dir.resolve("SKILL.md"), """
            ---
            name: Skill One
            description: First skill
            ---
            Body one.
            """);
        Files.writeString(skill2Dir.resolve("SKILL.md"), """
            ---
            name: Skill Two
            description: Second skill
            ---
            Body two.
            """);

        Optional<String> result = loader.loadSkills(tempDir.toString());

        assertThat(result).isPresent();
        String skills = result.get();
        assertThat(skills).contains("Skill One");
        assertThat(skills).contains("Skill Two");
        assertThat(skills).contains("First skill");
        assertThat(skills).contains("Second skill");
    }

    @Test
    void loadSkillsWithNoFrontmatterUsesDefaults() throws IOException {
        Path skillsDir = tempDir.resolve(".claw/skills");
        Path skillDir = skillsDir.resolve("plain-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "Just plain content without frontmatter.");

        Optional<String> result = loader.loadSkills(tempDir.toString());

        assertThat(result).isPresent();
        String skills = result.get();
        assertThat(skills).contains("Unknown Skill");
        assertThat(skills).contains("No description provided");
        assertThat(skills).contains("Just plain content without frontmatter");
    }

    @Test
    void loadSkillsIgnoresNonSkillFiles() throws IOException {
        Path skillsDir = tempDir.resolve(".claw/skills");
        Path skillDir = skillsDir.resolve("real-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: Real Skill
            description: The real one
            ---
            Real body.
            """);
        Files.writeString(skillDir.resolve("README.md"), "This should be ignored.");

        Optional<String> result = loader.loadSkills(tempDir.toString());

        assertThat(result).isPresent();
        String skills = result.get();
        assertThat(skills).contains("Real Skill");
        assertThat(skills).doesNotContain("README");
    }

    @Test
    void parseSkillMDExtractsFrontmatter() {
        String content = """
            ---
            name: My Skill
            description: Does something useful
            ---
            Here is the skill body.
            More lines.
            """;

        FilesystemSkillLoader.Skill skill = FilesystemSkillLoader.parseSkillMD(content);

        assertThat(skill.name).isEqualTo("My Skill");
        assertThat(skill.description).isEqualTo("Does something useful");
        assertThat(skill.body).contains("Here is the skill body");
    }

    @Test
    void parseSkillMDHandlesWindowsLineEndings() {
        String content = "---\r\nname: WinSkill\r\ndescription: Windows line endings\r\n---\r\nBody text.";

        FilesystemSkillLoader.Skill skill = FilesystemSkillLoader.parseSkillMD(content);

        assertThat(skill.name).isEqualTo("WinSkill");
        assertThat(skill.description).isEqualTo("Windows line endings");
        assertThat(skill.body).isEqualTo("Body text.");
    }

    @Test
    void parseSkillMDWithoutFrontmatterReturnsDefaults() {
        String content = "Just plain markdown content.";

        FilesystemSkillLoader.Skill skill = FilesystemSkillLoader.parseSkillMD(content);

        assertThat(skill.name).isEqualTo("Unknown Skill");
        assertThat(skill.description).isEqualTo("No description provided.");
        assertThat(skill.body).isEqualTo(content);
    }

    @Test
    void loadSkillsWithEmptySkillsDirReturnsEmpty() throws IOException {
        Path skillsDir = tempDir.resolve(".claw/skills");
        Files.createDirectories(skillsDir);

        Optional<String> result = loader.loadSkills(tempDir.toString());

        assertThat(result).isEmpty();
    }
}
