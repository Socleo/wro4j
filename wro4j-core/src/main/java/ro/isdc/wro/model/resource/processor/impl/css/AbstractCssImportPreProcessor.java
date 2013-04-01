/**
 * Copyright Alex Objelean
 */
package ro.isdc.wro.model.resource.processor.impl.css;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.isdc.wro.config.Context;
import ro.isdc.wro.config.jmx.WroConfiguration;
import ro.isdc.wro.model.group.Inject;
import ro.isdc.wro.model.resource.Resource;
import ro.isdc.wro.model.resource.ResourceType;
import ro.isdc.wro.model.resource.SupportedResourceType;
import ro.isdc.wro.model.resource.locator.factory.ResourceLocatorFactory;
import ro.isdc.wro.model.resource.processor.ImportAware;
import ro.isdc.wro.model.resource.processor.ResourceProcessor;
import ro.isdc.wro.model.resource.processor.support.CssImportInspector;
import ro.isdc.wro.util.StringUtils;


/**
 * CssImport Processor responsible for handling css <code>@import</code> statement. It is implemented as both:
 * preProcessor & postProcessor. It is necessary because preProcessor is responsible for updating model with found
 * imported resources, while post processor removes import occurrences.
 * <p/>
 * When processor finds an import which is not valid, it will check the
 * {@link WroConfiguration#isIgnoreMissingResources()} flag. If it is set to false, the processor will fail.
 *
 * @author Alex Objelean
 */
@SupportedResourceType(ResourceType.CSS)
public abstract class AbstractCssImportPreProcessor
  implements ResourceProcessor, ImportAware {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractCssImportPreProcessor.class);
  /**
   * Contains a {@link UriLocatorFactory} reference injected externally.
   */
  @Inject
  private ResourceLocatorFactory locatorFactory;
  /**
   * A map useful for detecting deep recursion. The key (correlationId) - identifies a processing unit, while the value
   * contains a pair between the list o processed resources and a stack holding recursive calls (value contained on this
   * stack is not important). This map is used to ensure that the processor is thread-safe and doesn't erroneously
   * detect recursion when running in concurrent environment (when processor is invoked from within the processor for
   * child resources).
   */
  private final Map<String, Pair<List<String>, Stack<String>>> contextMap = new HashMap<String, Pair<List<String>, Stack<String>>>() {
    /**
     * Make sure that the get call will always return a not null object. To avoid growth of this map, it is important to
     * call remove for each invoked get.
     */
    @Override
    public Pair<List<String>, Stack<String>> get(final Object key) {
      Pair<List<String>, Stack<String>> result = super.get(key);
      if (result == null) {
        final List<String> list = new ArrayList<String>();
        result = ImmutablePair.of(list, new Stack<String>());
        put(key.toString(), result);
      }
      return result;
    };
  };

  /**
   * {@inheritDoc}
   */
  public final void process(final Resource resource, final Reader reader, final Writer writer)
    throws IOException {
    validate();
    try {
      final String result = parseCss(resource, IOUtils.toString(reader));
      writer.write(result);
    } finally {
      clearProcessedImports();
      reader.close();
      writer.close();
    }
  }

  /**
   * Checks if required fields were injected.
   */
  private void validate() {
    Validate.notNull(locatorFactory);
  }

  /**
   * @param resource {@link Resource} to process.
   * @param cssContent Reader for processed resource.
   * @return css content with all imports processed.
   */
  private String parseCss(final Resource resource, final String cssContent)
    throws IOException {
    if (isImportProcessed(resource.getUri())) {
      LOG.debug("[WARN] Recursive import detected: {}", resource);
      onRecursiveImportDetected();
      return "";
    }
    final String importedUri = resource.getUri().replace(File.separatorChar,'/');
    addProcessedImport(importedUri);
    final List<Resource> importedResources = findImportedResources(resource.getUri(), cssContent);
    return doTransform(cssContent, importedResources);
  }

  private boolean isImportProcessed(final String uri) {
    return getProcessedImports().contains(uri);
  }

  private void addProcessedImport(final String importedUri) {
    final String correlationId = Context.getCorrelationId();
    contextMap.get(correlationId).getValue().push(correlationId);
    getProcessedImports().add(importedUri);
  }

  private List<String> getProcessedImports() {
    return contextMap.get(Context.getCorrelationId()).getKey();
  }

  private void clearProcessedImports() {
    final String correlationId = Context.getCorrelationId();
    final Stack<String> stack = contextMap.get(correlationId).getValue();
    if (!stack.isEmpty()) {
      stack.pop();
    }
    if (stack.isEmpty()) {
      contextMap.remove(correlationId);
    }
  }

  /**
   * Find a set of imported resources inside a given resource.
   */
  private List<Resource> findImportedResources(final String resourceUri, final String cssContent)
    throws IOException {
    // it should be sorted
    final List<Resource> imports = new ArrayList<Resource>();
    final String css = cssContent;
    final List<String> foundImports = findImports(css);
    for (final String importUrl : foundImports) {
      final Resource importedResource = createImportedResource(resourceUri, importUrl);
      // check if already exist
      if (imports.contains(importedResource)) {
        LOG.debug("[WARN] Duplicate imported resource: {}", importedResource);
      } else {
        imports.add(importedResource);
        onImportDetected(importedResource.getUri());
      }
    }
    return imports;
  }

  /**
   * Extracts a list of imports from css content.
   *
   * @return a list of found imports.
   */
  protected List<String> findImports(final String css) {
    return new CssImportInspector(css).findImports();
  }

  /**
   * Build a {@link Resource} object from a found importedResource inside a given resource.
   */
  private Resource createImportedResource(final String resourceUri, final String importUrl) {
    final String absoluteUrl = computeAbsoluteUrl(resourceUri, importUrl);
    return Resource.create(absoluteUrl, ResourceType.CSS);
  }

  /**
   * Computes absolute url of the imported resource.
   *
   * @param relativeResourceUri uri of the resource containing the import statement.
   * @param importUrl found import url.
   * @return absolute url of the resource to import.
   */
  private String computeAbsoluteUrl(final String relativeResourceUri, final String importUrl) {
    final String folder = FilenameUtils.getFullPath(relativeResourceUri);
    // remove '../' & normalize the path.
    final String absoluteImportUrl = StringUtils.cleanPath(folder + importUrl);
    return absoluteImportUrl;
  }

  /**
   * Perform actual transformation of provided cssContent and the list of found import resources.
   *
   * @param cssContent
   *          the css to transform.
   * @param importedResources
   *          the list of found imports.
   */
  protected abstract String doTransform(final String cssContent, final List<Resource> importedResources)
      throws IOException;


  /**
   * Invoked when an import is detected. By default this method does nothing.
   * @param foundImportUri the uri of the detected imported resource
   */
  protected void onImportDetected(final String foundImportUri) {
  }

  /**
   * Invoked when a recursive import is detected. Used to assert the recursive import detection correct behavior. By
   * default this method does nothing.
   *
   * @VisibleForTesting
   */
  protected void onRecursiveImportDetected() {
  }

  /**
   * {@inheritDoc}
   */
  public boolean isImportAware() {
    //We want this processor to be applied when processing resources referred with @import directive
    return true;
  }
}
