/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view.textclassifier;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.os.LocaleList;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ModelFileManagerTest {
    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("en-US");
    @Mock
    private Supplier<List<ModelFileManager.ModelFile>> mModelFileSupplier;
    private ModelFileManager.ModelFileSupplierImpl mModelFileSupplierImpl;
    private ModelFileManager mModelFileManager;
    private File mRootTestDir;
    private File mFactoryModelDir;
    private File mUpdatedModelFile;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mModelFileManager = new ModelFileManager(mModelFileSupplier);
        mRootTestDir = InstrumentationRegistry.getContext().getCacheDir();
        mFactoryModelDir = new File(mRootTestDir, "factory");
        mUpdatedModelFile = new File(mRootTestDir, "updated.model");

        mModelFileSupplierImpl =
                new ModelFileManager.ModelFileSupplierImpl(
                        mFactoryModelDir,
                        "test\\d.model",
                        mUpdatedModelFile,
                        fd -> 1,
                        fd -> ModelFileManager.ModelFile.LANGUAGE_INDEPENDENT
                );

        mRootTestDir.mkdirs();
        mFactoryModelDir.mkdirs();

        Locale.setDefault(DEFAULT_LOCALE);
    }

    @After
    public void removeTestDir() {
        recursiveDelete(mRootTestDir);
    }

    @Test
    public void get() {
        ModelFileManager.ModelFile modelFile =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1, Collections.emptyList(), true);
        when(mModelFileSupplier.get()).thenReturn(Collections.singletonList(modelFile));

        List<ModelFileManager.ModelFile> modelFiles = mModelFileManager.listModelFiles();

        assertThat(modelFiles).hasSize(1);
        assertThat(modelFiles.get(0)).isEqualTo(modelFile);
    }

    @Test
    public void findBestModel_versionCode() {
        ModelFileManager.ModelFile olderModelFile =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1,
                        Collections.emptyList(), true);

        ModelFileManager.ModelFile newerModelFile =
                new ModelFileManager.ModelFile(
                        new File("/path/b"), 2,
                        Collections.emptyList(), true);
        when(mModelFileSupplier.get())
                .thenReturn(Arrays.asList(olderModelFile, newerModelFile));

        ModelFileManager.ModelFile bestModelFile =
                mModelFileManager.findBestModelFile(LocaleList.getEmptyLocaleList());

        assertThat(bestModelFile).isEqualTo(newerModelFile);
    }

    @Test
    public void findBestModel_languageDependentModelIsPreferred() {
        Locale locale = Locale.forLanguageTag("ja");
        ModelFileManager.ModelFile languageIndependentModelFile =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1,
                        Collections.emptyList(), true);

        ModelFileManager.ModelFile languageDependentModelFile =
                new ModelFileManager.ModelFile(
                        new File("/path/b"), 1,
                        Collections.singletonList(locale), false);
        when(mModelFileSupplier.get())
                .thenReturn(
                        Arrays.asList(languageIndependentModelFile, languageDependentModelFile));

        ModelFileManager.ModelFile bestModelFile =
                mModelFileManager.findBestModelFile(
                        LocaleList.forLanguageTags(locale.toLanguageTag()));
        assertThat(bestModelFile).isEqualTo(languageDependentModelFile);
    }

    @Test
    public void findBestModel_noMatchedLanguageModel() {
        Locale locale = Locale.forLanguageTag("ja");
        ModelFileManager.ModelFile languageIndependentModelFile =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1,
                        Collections.emptyList(), true);

        ModelFileManager.ModelFile languageDependentModelFile =
                new ModelFileManager.ModelFile(
                        new File("/path/b"), 1,
                        Collections.singletonList(locale), false);

        when(mModelFileSupplier.get())
                .thenReturn(
                        Arrays.asList(languageIndependentModelFile, languageDependentModelFile));

        ModelFileManager.ModelFile bestModelFile =
                mModelFileManager.findBestModelFile(
                        LocaleList.forLanguageTags("zh-hk"));
        assertThat(bestModelFile).isEqualTo(languageIndependentModelFile);
    }

    @Test
    public void findBestModel_noMatchedLanguageModel_defaultLocaleModelExists() {
        ModelFileManager.ModelFile languageIndependentModelFile =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1,
                        Collections.emptyList(), true);

        ModelFileManager.ModelFile languageDependentModelFile =
                new ModelFileManager.ModelFile(
                        new File("/path/b"), 1,
                        Collections.singletonList(DEFAULT_LOCALE), false);

        when(mModelFileSupplier.get())
                .thenReturn(
                        Arrays.asList(languageIndependentModelFile, languageDependentModelFile));

        ModelFileManager.ModelFile bestModelFile =
                mModelFileManager.findBestModelFile(
                        LocaleList.forLanguageTags("zh-hk"));
        assertThat(bestModelFile).isEqualTo(languageIndependentModelFile);
    }

    @Test
    public void findBestModel_languageIsMoreImportantThanVersion() {
        ModelFileManager.ModelFile matchButOlderModel =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1,
                        Collections.singletonList(Locale.forLanguageTag("fr")), false);

        ModelFileManager.ModelFile mismatchButNewerModel =
                new ModelFileManager.ModelFile(
                        new File("/path/b"), 2,
                        Collections.singletonList(Locale.forLanguageTag("ja")), false);

        when(mModelFileSupplier.get())
                .thenReturn(
                        Arrays.asList(matchButOlderModel, mismatchButNewerModel));

        ModelFileManager.ModelFile bestModelFile =
                mModelFileManager.findBestModelFile(
                        LocaleList.forLanguageTags("fr"));
        assertThat(bestModelFile).isEqualTo(matchButOlderModel);
    }

    @Test
    public void findBestModel_languageIsMoreImportantThanVersion_bestModelComesFirst() {
        ModelFileManager.ModelFile matchLocaleModel =
                new ModelFileManager.ModelFile(
                        new File("/path/b"), 1,
                        Collections.singletonList(Locale.forLanguageTag("ja")), false);

        ModelFileManager.ModelFile languageIndependentModel =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 2,
                        Collections.emptyList(), true);
        when(mModelFileSupplier.get())
                .thenReturn(
                        Arrays.asList(matchLocaleModel, languageIndependentModel));

        ModelFileManager.ModelFile bestModelFile =
                mModelFileManager.findBestModelFile(
                        LocaleList.forLanguageTags("ja"));

        assertThat(bestModelFile).isEqualTo(matchLocaleModel);
    }

    @Test
    public void modelFileEquals() {
        ModelFileManager.ModelFile modelA =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1,
                        Collections.singletonList(Locale.forLanguageTag("ja")), false);

        ModelFileManager.ModelFile modelB =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1,
                        Collections.singletonList(Locale.forLanguageTag("ja")), false);

        assertThat(modelA).isEqualTo(modelB);
    }

    @Test
    public void modelFile_different() {
        ModelFileManager.ModelFile modelA =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1,
                        Collections.singletonList(Locale.forLanguageTag("ja")), false);

        ModelFileManager.ModelFile modelB =
                new ModelFileManager.ModelFile(
                        new File("/path/b"), 1,
                        Collections.singletonList(Locale.forLanguageTag("ja")), false);

        assertThat(modelA).isNotEqualTo(modelB);
    }


    @Test
    public void modelFile_getPath() {
        ModelFileManager.ModelFile modelA =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1,
                        Collections.singletonList(Locale.forLanguageTag("ja")), false);

        assertThat(modelA.getPath()).isEqualTo("/path/a");
    }

    @Test
    public void modelFile_getName() {
        ModelFileManager.ModelFile modelA =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1,
                        Collections.singletonList(Locale.forLanguageTag("ja")), false);

        assertThat(modelA.getName()).isEqualTo("a");
    }

    @Test
    public void modelFile_isPreferredTo_languageDependentIsBetter() {
        ModelFileManager.ModelFile modelA =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 1,
                        Collections.singletonList(Locale.forLanguageTag("ja")), false);

        ModelFileManager.ModelFile modelB =
                new ModelFileManager.ModelFile(
                        new File("/path/b"), 2,
                        Collections.emptyList(), true);

        assertThat(modelA.isPreferredTo(modelB)).isTrue();
    }

    @Test
    public void modelFile_isPreferredTo_version() {
        ModelFileManager.ModelFile modelA =
                new ModelFileManager.ModelFile(
                        new File("/path/a"), 2,
                        Collections.singletonList(Locale.forLanguageTag("ja")), false);

        ModelFileManager.ModelFile modelB =
                new ModelFileManager.ModelFile(
                        new File("/path/b"), 1,
                        Collections.emptyList(), false);

        assertThat(modelA.isPreferredTo(modelB)).isTrue();
    }

    @Test
    public void testFileSupplierImpl_updatedFileOnly() throws IOException {
        mUpdatedModelFile.createNewFile();
        File model1 = new File(mFactoryModelDir, "test1.model");
        model1.createNewFile();
        File model2 = new File(mFactoryModelDir, "test2.model");
        model2.createNewFile();
        new File(mFactoryModelDir, "not_match_regex.model").createNewFile();

        List<ModelFileManager.ModelFile> modelFiles = mModelFileSupplierImpl.get();
        List<String> modelFilePaths =
                modelFiles
                        .stream()
                        .map(modelFile -> modelFile.getPath())
                        .collect(Collectors.toList());

        assertThat(modelFiles).hasSize(3);
        assertThat(modelFilePaths).containsExactly(
                mUpdatedModelFile.getAbsolutePath(),
                model1.getAbsolutePath(),
                model2.getAbsolutePath());
    }

    @Test
    public void testFileSupplierImpl_empty() {
        mFactoryModelDir.delete();
        List<ModelFileManager.ModelFile> modelFiles = mModelFileSupplierImpl.get();

        assertThat(modelFiles).hasSize(0);
    }

    private static void recursiveDelete(File f) {
        if (f.isDirectory()) {
            for (File innerFile : f.listFiles()) {
                recursiveDelete(innerFile);
            }
        }
        f.delete();
    }
}
