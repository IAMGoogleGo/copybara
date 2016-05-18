package com.google.copybara;

import com.google.common.collect.ImmutableList;
import com.google.copybara.Origin.Reference;
import com.google.copybara.Origin.ReferenceFiles;
import com.google.copybara.transform.Transformation;
import com.google.copybara.util.FileUtil;
import com.google.copybara.util.PathMatcherBuilder;
import com.google.copybara.util.console.Console;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.annotation.Nullable;

/**
 * For each pending change in the origin, runs the transformations and sends to the destination
 * individually.
 */
class IterativeWorkflow<O extends Origin<O>> extends Workflow<O> {

  IterativeWorkflow(String configName, String workflowName, Origin<O> origin,
      Destination destination, ImmutableList<Transformation> transformations,
      @Nullable String lastRevision, Console console, PathMatcherBuilder excludedOriginPaths) {
    super(configName, workflowName, origin, destination, transformations, lastRevision,
        console, excludedOriginPaths);
  }

  @Override
  public void runForRef(Path workdir, ReferenceFiles<O> to)
      throws RepoException, IOException {
    Reference<O> from = getLastRef();
    ImmutableList<Change<O>> changes = getOrigin().changes(from, to);
    for (int i = 0; i < changes.size(); i++) {
      Change<O> change = changes.get(i);
      String prefix = String.format(
          "[%2d/%d] Migrating change %s: ", i + 1, changes.size(),
          change.getReference().asString());
      ReferenceFiles<O> ref = change.getReference();
      logger.log(Level.INFO, String.format("%s %s", prefix, ref.asString()));
      console.progress(prefix + "Cleaning working directory");
      FileUtil.deleteAllFilesRecursively(workdir);
      console.progress(prefix + "Checking out the change");
      ref.checkout(workdir);
      removeExcludedFiles(workdir);
      runTransformations(workdir, prefix);

      Long timestamp = ref.readTimestamp();
      if (timestamp == null) {
        timestamp = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
      }
      String message = change.getMessage();
      if (!message.endsWith("\n")) {
        message += "\n";
      }
      // TODO(malcon): Show the prefix on destination
      getDestination().process(workdir, ref, timestamp, message);
    }
  }

  private Reference<O> getLastRef() throws RepoException {
    if (lastRevision != null) {
      return getOrigin().resolve(lastRevision);
    }
    String labelName = getOrigin().getLabelName();
    String previousRef = getDestination().getPreviousRef(labelName);
    if (previousRef == null) {
      throw new RepoException(String.format(
          "Previous revision label %s could not be found in %s and --last_revision flag"
              + " was not passed", labelName, getDestination()));
    }
    return getOrigin().resolve(previousRef);
  }
}
