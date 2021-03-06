package net.orekyuu.gitthrow.job.task;

import net.orekyuu.gitthrow.build.model.domain.Artifact;
import net.orekyuu.gitthrow.build.usecase.ArtifactUsecase;
import net.orekyuu.gitthrow.build.usecase.WorkbenchConfigUsecase;
import net.orekyuu.gitthrow.job.JobMessenger;
import net.orekyuu.gitthrow.job.JobWorkspaceService;
import net.orekyuu.gitthrow.job.WorkbenchConfig;
import net.orekyuu.gitthrow.job.message.LogMessage;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
@Scope("prototype")
public class SaveArtifactTask implements Task {

    private static final Logger logger = LoggerFactory.getLogger(SaveArtifactTask.class);

    public static final String ARTIFACT_KEY = "SaveArtifactTask.artifact";

    @Autowired
    private ArtifactUsecase artifactUsecase;
    @Autowired
    private WorkbenchConfigUsecase configUsecase;
    @Autowired
    private JobWorkspaceService jobWorkspaceService;

    @Override
    public boolean process(JobMessenger messenger, TaskArguments args) throws Exception {

        Optional<WorkbenchConfig> configOpt = configUsecase.find(args.getProject(), "HEAD");
        if (!configOpt.isPresent()) {
            return true;
        }
        WorkbenchConfig config = configOpt.get();
        List<String> artifactPathList = config.getBuildSettings().getArtifactPath();
        //空なら保存対象無し
        if (artifactPathList == null || artifactPathList.isEmpty()) {
            return true;
        }

        Path workspacePath = jobWorkspaceService.getWorkspacePath(args.getJobId());

        List<Path> files = artifactPathList.stream()
            .flatMap(s -> {
                try {
                    return findFiles(workspacePath, s, args.getJobId().toString()).stream();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return Stream.empty();
            }).collect(Collectors.toList());

        if (files.isEmpty()) {
            return true;
        }

        files.stream()
            .map(path -> path.getFileName().getFileName())
            .forEach(str -> messenger.send(new LogMessage("Save: " + str)));

        Pair<String, byte[]> pair = toByteArray(files);
        if (pair != null) {
            Artifact artifact = artifactUsecase.create(args.getProject(), pair.getLeft(), pair.getRight());
            args.putData(ARTIFACT_KEY, artifact);
        }

        return true;
    }

    /**
     * 設定の正規表現にマッチするファイルのPathを抽出する
     *
     * @param dir ワークスペースのPath
     * @param reg 正規表現
     * @return 正規表現に一致するファイルのリスト
     * @throws IOException
     */
    private List<Path> findFiles(Path dir, String reg, String jobId) throws IOException {
        PathMatcher pathMatcher = dir.getFileSystem().getPathMatcher("glob:job/" + jobId + "/" + reg);

        return Files.walk(dir)
            .filter(child -> Files.isRegularFile(child))
            .filter(pathMatcher::matches)
            .collect(Collectors.toList());
    }

    private Pair<String, byte[]> toByteArray(List<Path> files) {
        if (files.isEmpty()) {
            return null;
        }

        if (files.size() == 1) {
            Path path = files.get(0);
            try {
                return new ImmutablePair<>(path.getFileName().toString(), Files.readAllBytes(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(outputStream);) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry("result/" + file.getFileName().toString());
                zos.putNextEntry(entry);
                try (InputStream in = Files.newInputStream(file)) {
                    IOUtils.copy(in, zos);
                }
                zos.closeEntry();
            }
            zos.close();
            return new ImmutablePair<>("result.zip", outputStream.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
