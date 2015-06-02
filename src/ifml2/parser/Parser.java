package ifml2.parser;

import ca.odell.glazedlists.EventList;
import ifml2.CommonUtils;
import ifml2.IFML2Exception;
import ifml2.engine.Engine;
import ifml2.om.*;

import java.text.MessageFormat;
import java.util.*;

public class Parser
{
    public ParseResult parse(String phrase, Story.DataHelper storyDataHelper, Engine.DataHelper engineDataHelper) throws IFML2Exception
    {
        outParsDebug(engineDataHelper, "Начало анализа команды \"{0}\"...", phrase);

        if (storyDataHelper.getAllActions() == null || storyDataHelper.getAllActions().size() == 0)
        {
            throw new IFML2Exception("Системная ошибка: В системе нет ни одного действия.");
        }

        // split phrase to array
        ArrayList<String> phraseAsList = new ArrayList<String>(Arrays.asList(phrase.split("\\s+")));
        outParsDebug(engineDataHelper, "Разбили фразу на слова: {0}.", phraseAsList);

        // prepare list of all fitted templates
        ArrayList<FittedTemplate> fittedTemplates = new ArrayList<FittedTemplate>();

        IFML2Exception lastException = null;

        outParsDebug(engineDataHelper, "Старт перебора всех действий в игре...");
        for (Action action : storyDataHelper.getAllActions())
        {
            outParsDebug(1, engineDataHelper, "Анализируем действие \"{0}\"...", action);
            for (Template template : action.getTemplates())
            {
                outParsDebug(2, engineDataHelper, "Анализируем шаблон \"{0}\"...", template);
                int templateSize = template.getSize();
                try
                {
                    ArrayList<FittedFormalElement> fittedFormalElements = fitPhraseWithTemplate(phraseAsList, template.getElements(),
                            storyDataHelper, engineDataHelper, 3);

                    FittedTemplate fittedTemplate = new FittedTemplate(action, fittedFormalElements);
                    fittedTemplates.add(fittedTemplate);
                }
                catch (IFML2ParsePhraseTooLong e)
                {
                    if (lastException == null ||
                        (lastException instanceof IFML2ParseException && e.isMoreFull((IFML2ParseException) lastException, templateSize)))
                    {
                        lastException = new IFML2ParseException(MessageFormat
                                                                        .format("Я бы понял, если бы вы сказали \"{0}\", но я не понял вот эту часть фразы: \"{1}\".",
                                                                                convertFittedToString(e.getFittedFormalElements()),
                                                                                convertArrayToString(e.getPhraseRest())), e.getUsedWords(),
                                                                templateSize);
                    }
                }
                catch (IFML2ParseException e)
                {
                    if (lastException == null)
                    {
                        e.setTemplateSize(templateSize);
                        lastException = e;
                    }
                    else if (lastException instanceof IFML2ParseException)
                    {
                        e.setTemplateSize(templateSize);
                        if (e.isMoreFull((IFML2ParseException) lastException, templateSize))
                        {
                            lastException = e;
                        }
                        else if (e.isEquallyFull((IFML2ParseException) lastException, templateSize))
                        {
                            lastException = Math.round(Math.random()) == 0 ? lastException : e; // randomly take one of error :)
                        }
                    }
                }
                catch (IFML2Exception e)
                {
                    lastException = e;
                }
            }
        }

        if (fittedTemplates.size() == 0)
        {
            throw lastException;
        }

        // clean fitted templates from templates with inaccessible objects
        ArrayList<FittedTemplate> accessibleTemplates = null;
        try
        {
            accessibleTemplates = removeInaccessibleObjects(fittedTemplates, engineDataHelper);
        }
        catch (IFML2Exception e)
        {
            lastException = e; // always rewrite last exception because inaccessible objects are more important for errors
        }

        if (accessibleTemplates == null || accessibleTemplates.size() == 0)
        {
            throw lastException;
        }

        // take the first of fitted templates
        FittedTemplate firstFittedTemplate = accessibleTemplates.get(0);

        // test fitted template on variance of objects
        for (FittedFormalElement fittedFormalElement : firstFittedTemplate.fittedFormalElements)
        {
            if (fittedFormalElement instanceof FittedObjects)
            {
                List<IFMLObject> objects = ((FittedObjects) fittedFormalElement).objects;
                if (objects.size() > 1)
                {
                    throw new IFML2ParseException("Не понятно, что за " + objects.get(0).getWordLinks().getMainWord() + " имеется в виду.",
                                                  phraseAsList.size());
                }
            }
        }

        ArrayList<FormalElement> formalElements = new ArrayList<FormalElement>();
        for (FittedFormalElement fittedFormalElement : firstFittedTemplate.fittedFormalElements)
        {
            FormalElement formalElement;
            if (fittedFormalElement instanceof FittedSynonym)
            {
                FittedSynonym fittedSynonym = (FittedSynonym) fittedFormalElement;
                formalElement = new FormalElement(fittedSynonym.synonym, fittedSynonym.parameter);
            }
            else if (fittedFormalElement instanceof FittedObjects)
            {
                FittedObjects fittedObjects = (FittedObjects) fittedFormalElement;
                formalElement = new FormalElement(fittedObjects.objects.get(0), fittedObjects.parameter);
            }
            else
            {
                throw new IFML2Exception(
                        "Системная ошибка: ПодходящийФомральныйЭлемент имеет неизвестный тип\n" + "Фраза: " + phrase + "\n" + "Шаблон: " +
                        firstFittedTemplate.fittedFormalElements + ".");
            }

            formalElements.add(formalElement);
        }

        return new ParseResult(firstFittedTemplate.action, formalElements);
    }

    private void outParsDebug(int level, Engine.DataHelper engineDataHelper, String message, Object... args)
    {
        engineDataHelper.outDebug(this.getClass(), level, message, args);
    }

    private void outParsDebug(Engine.DataHelper engineDataHelper, String message, Object... args)
    {
        outParsDebug(0, engineDataHelper, message, args);
    }

    private String convertArrayToString(ArrayList<String> stringArrayList)
    {
        String result = "";
        for (String element : stringArrayList)
        {
            if (result.length() > 0)
            {
                result += " ";
            }
            result += element;
        }
        return result;
    }

    private String convertFittedToString(ArrayList<FittedFormalElement> fittedFormalElements) throws IFML2Exception
    {
        String result = "";

        for (FittedFormalElement fittedFormalElement : fittedFormalElements)
        {
            String element = "";
            if (fittedFormalElement instanceof FittedSynonym)
            {
                element = fittedFormalElement.toString();
            }
            else if (fittedFormalElement instanceof FittedObjects)
            {
                ArrayList<IFMLObject> fittedObjects = ((FittedObjects) fittedFormalElement).objects;

                if (fittedObjects.size() > 0)
                {
                    element = fittedObjects.get(0).getName(((FittedObjects) fittedFormalElement).gramCase);
                }
                else
                {
                    throw new IFML2Exception("Системная ошибка: в FittedObjects кол-во объектов = 0\n" +
                                             "fittedFormalElements = " + fittedFormalElements + '\n' +
                                             "fittedFormalElement = " + fittedFormalElement);
                }
            }
            result += " " + element;
        }

        return result.trim();
    }

    private ArrayList<FittedTemplate> removeInaccessibleObjects(ArrayList<FittedTemplate> fittedTemplates,
            Engine.DataHelper engineDataHelper) throws IFML2Exception
    {
        ArrayList<FittedTemplate> result = new ArrayList<FittedTemplate>();
        IFMLObject inaccessibleObject = null;

        for (FittedTemplate fittedTemplate : fittedTemplates)
        {
            Boolean toAddTemplate = true;

            for (FittedFormalElement fittedFormalElement : fittedTemplate.getFittedFormalElements())
            {
                if (fittedFormalElement instanceof FittedObjects)
                {
                    ArrayList<IFMLObject> objectsToRemove = new ArrayList<IFMLObject>();

                    ArrayList<IFMLObject> fittedObjects = ((FittedObjects) fittedFormalElement).getObjects();

                    for (IFMLObject object : fittedObjects)
                    {
                        if (!engineDataHelper.isObjectAccessible(object))
                        {
                            objectsToRemove.add(object);

                            if (inaccessibleObject == null)
                            {
                                inaccessibleObject = object;
                            }
                        }
                    }

                    fittedObjects.removeAll(objectsToRemove);

                    if (fittedObjects.size() == 0)
                    {
                        toAddTemplate = false;
                    }
                }
            }

            if (toAddTemplate)
            {
                result.add(fittedTemplate);
            }
        }

        if (result.size() > 0)
        {
            return result;
        }
        else
        {
            if (inaccessibleObject != null)
            {
                throw new IFML2ParseException("Не вижу здесь " + inaccessibleObject.getName(Word.GramCaseEnum.RP) + ".");
            }
            else
            {
                throw new IFML2Exception(
                        "Системная ошибка: inaccessibleObject = null в Parser.removeInaccessibleObjects() при result.size() = 0.");
            }
            // it doesn't require word count because it's outstanding exception
        }
    }

    private ArrayList<FittedFormalElement> fitPhraseWithTemplate(ArrayList<String> phraseAsList, List<TemplateElement> template,
            Story.DataHelper storyDataHelper, Engine.DataHelper engineDataHelper, int debugLevel) throws IFML2Exception
    {
        outParsDebug(debugLevel, engineDataHelper, "Сравниваем фразу {0} с шаблоном {1}...", phraseAsList, template);

        // get vars into local copy
        ArrayList<String> phraseRest = new ArrayList<String>(phraseAsList);
        ArrayList<TemplateElement> templateRest = new ArrayList<TemplateElement>(template);

        // take the first element of template
        TemplateElement firstTemplateElement = templateRest.get(0);
        outParsDebug(debugLevel, engineDataHelper, "Взяли первый элемент шаблона - {0} - и пытаемся его сопоставить с началом фразы...",
                     firstTemplateElement);

        // try to fit template element with beginning of phrase
        TemplateElementFitResult result = fitTemplateElementWithPhrase(firstTemplateElement, phraseRest, engineDataHelper, storyDataHelper,
                debugLevel + 1);
        outParsDebug(debugLevel, engineDataHelper, "Результат: {0}", result);

        ArrayList<FittedFormalElement> fittedFormalElements = new ArrayList<FittedFormalElement>();

        fittedFormalElements.add(result.fittedFormalElement);

        // cut template and phrase
        templateRest.remove(0);
        int usedWordsQty = result.usedWordsQty;
        for (int i = 1; i <= usedWordsQty; i++)
        {
            phraseRest.remove(0);
        }
        outParsDebug(debugLevel, engineDataHelper, "Остаток фразы: \"{0}\", шаблона: \"{1}\" для продолжения сопоставления.", phraseRest,
                templateRest);

        if (templateRest.size() == 0 && phraseRest.size() == 0)
        {
            outParsDebug(debugLevel, engineDataHelper, "И остаток фразы и остаток шаблона пустые - шаблон подошёл успешно!");
            return fittedFormalElements;
        }
        else if (templateRest.size() > 0 && phraseRest.size() == 0)
        {
            outParsDebug(debugLevel, engineDataHelper,
                    "Шаблон ещё не закончился, а фраза уже закончилась -> фраза не полная. Создаём ошибку распознавания с кол-вом слов {0}.",
                         usedWordsQty);
            throw new IFML2ParseException(makeQuestionsForTemplate(templateRest) + " (пишите ответ полностью)", usedWordsQty);
        }
        else if (templateRest.size() == 0 && phraseRest.size() > 0)
        {
            outParsDebug(debugLevel, engineDataHelper,
                    "Шаблон уже закончился, а фраза ещё нет - слишком длинная фраза. Создаём ошибку распознавания с кол-вом слов {0}.",
                         usedWordsQty);
            throw new IFML2ParsePhraseTooLong(fittedFormalElements, phraseRest, usedWordsQty);
        }
        else
        {
            outParsDebug(debugLevel, engineDataHelper, "И шаблон, и фраза ещё не закончились -> продолжаем сопоставление.");
            try
            {
                ArrayList<FittedFormalElement> nextElements = fitPhraseWithTemplate(phraseRest, templateRest, storyDataHelper,
                        engineDataHelper, debugLevel + 1);
                fittedFormalElements.addAll(nextElements);
                return fittedFormalElements;
            }
            catch (IFML2ParsePhraseTooLong e)
            {
                e.getFittedFormalElements().add(0, result.fittedFormalElement);
                e.setUsedWords(e.getUsedWords() + usedWordsQty);
                throw e;
            }
            catch (IFML2ParseException e)
            {
                e.setUsedWords(e.getUsedWords() + usedWordsQty);
                throw e;
            }
        }
    }

    private TemplateElementFitResult fitTemplateElementWithPhrase(TemplateElement templateElement, ArrayList<String> phrase,
            Engine.DataHelper engineDataHelper, Story.DataHelper storyDataHelper, int debugLevel) throws IFML2Exception
    {
        outParsDebug(debugLevel, engineDataHelper, "Сопоставляем элемент шаблона {0} с фразой {1}...", templateElement, phrase);
        IFML2Exception lastException = null;

        if (templateElement instanceof LiteralTemplateElement)
        {
            HashMap<String, Integer> fittedSynonyms = new HashMap<String, Integer>();
            EventList<String> synonyms = ((LiteralTemplateElement) templateElement).getSynonyms();
            outParsDebug(debugLevel, engineDataHelper, "Элемент - литерал, сравниваем все его синонимы {0}...", synonyms);
            for (String synonym : synonyms)
            {
                try
                {
                    // add fitted synonym or ...
                    fittedSynonyms.put(synonym, fitSynonymWithPhrase(synonym, phrase, engineDataHelper, debugLevel + 1));
                }
                catch (IFML2ParseException e)
                {
                    outParsDebug(debugLevel, engineDataHelper, "Поймали ошибку (\"{0}\")...", e.getMessage());

                    // ... catch exception
                    if (lastException == null)
                    {
                        outParsDebug(debugLevel, engineDataHelper, "... до этого не было ошибок -> сохраняем её.");
                        lastException = e;
                    }
                    else
                    {
                        outParsDebug(debugLevel, engineDataHelper, "... до этого была ошибка -> сравниваем её с сохрённой (\"{0}\"):",
                                lastException.getMessage());
                        int lastUsedWords = ((IFML2ParseException) lastException).getUsedWords();
                        int currUsedWords = e.getUsedWords();
                        outParsDebug(debugLevel + 1, engineDataHelper,
                                "Сохранённая ошибка произошла после {0} пройденных слов, текущая - после {1} слов",
                                lastUsedWords, currUsedWords);
                        if (currUsedWords > lastUsedWords)
                        {
                            outParsDebug(debugLevel + 1, engineDataHelper,
                                    "Текущая произошла дальше (по словам) -> сохраняем её как самую адекватную");
                            lastException = e;
                        }
                        else if (currUsedWords == lastUsedWords)
                        {
                            // take random of these exceptions :)
                            lastException = Math.round(Math.random()) == 0 ? lastException : e;
                            outParsDebug(debugLevel + 1, engineDataHelper,
                                    "Текущая произошла так же далеко (по словам), как и сохранённая -> выбираем из двух случайным образом и сохраняем ;) Выбрали: {0}",
                                    lastException.getMessage());
                        }
                    }
                }
            }

            // if there are fitted synonyms
            if(fittedSynonyms.size() > 0)
            {
                Map.Entry<String, Integer> maxEntry = Collections.max(fittedSynonyms.entrySet(), new Comparator<Map.Entry<String, Integer>>()
                {
                    @Override
                    public int compare(Map.Entry<String, Integer> o1, Map.Entry<String, Integer> o2)
                    {
                        return o1.getValue() - o2.getValue();
                    }
                });
                return new TemplateElementFitResult(new FittedSynonym(maxEntry.getKey(), templateElement.getParameter()),
                        maxEntry.getValue());
            }

            // if there are no fitted synonyms
            if (lastException != null)
            {
                outParsDebug(debugLevel, engineDataHelper, "Нет подошедших синонимов - выкидываем сохранённую ошибку (\"{0}\")",
                        lastException.getMessage());
                throw lastException;
            }
            else
            {
                throw new IFML2Exception(
                        "Системная ошибка: при сопоставлении ЭлементаШаблона с началом фразы проверка синонимов ни к чему не привела.");
            }
        }

        else if (templateElement instanceof ObjectTemplateElement)
        {
            Word.GramCaseEnum gramCase = ((ObjectTemplateElement) templateElement).getGramCase();
            outParsDebug(debugLevel, engineDataHelper, "Элемент - объект, ищем подходящий объект для падежа {0}...",
                    gramCase.getAbbreviation());

            FitObjectWithPhraseResult fitObjectWithPhraseResult = fitObjectWithPhrase(gramCase, phrase, engineDataHelper, storyDataHelper,
                    debugLevel + 1);
            ArrayList<IFMLObject> objects = fitObjectWithPhraseResult.getObjects();
            int usedWordsQty = fitObjectWithPhraseResult.getUsedWordsQty();

            return new TemplateElementFitResult(new FittedObjects(objects, gramCase, templateElement.getParameter()), usedWordsQty);
        }
        else
        {
            throw new IFML2Exception("Системная ошибка: ЭлементШаблона неизвестного типа.");
        }
    }

    private FitObjectWithPhraseResult fitObjectWithPhrase(Word.GramCaseEnum gramCase, ArrayList<String> phrase,
            Engine.DataHelper engineDataHelper,
            Story.DataHelper storyDataHelper, int debugLevel) throws IFML2Exception
    {
        outParsDebug(debugLevel, engineDataHelper, "Сопоставление фразы {0} с объектом по падежу {1}...", phrase,
                gramCase.getAbbreviation());
        List<String> restPhrase = new ArrayList<String>(phrase);
        ArrayList<Word> fittedWords = new ArrayList<Word>();
        int allUsedWords = 0;

        // Stage I
        outParsDebug(debugLevel, engineDataHelper, "Фаза I: поиск слов по падежу...");
        int st1DebugLvl = debugLevel + 1;

        while (true)
        {
            if (restPhrase.size() == 0)
            {
                outParsDebug(st1DebugLvl, engineDataHelper, "Фраза кончилась -> завершение фазы I.");
                break;
            }

            boolean wordIsFound = false;

            outParsDebug(st1DebugLvl, engineDataHelper, "Ищем слово в словаре...");
            for (Word dictWord : storyDataHelper.getDictionary().values())
            {
                int usedWords = fitWordWithPhrase(dictWord, gramCase, restPhrase);

                allUsedWords += usedWords;

                if (usedWords > 0)
                {
                    outParsDebug(st1DebugLvl, engineDataHelper,
                            "Слово нашлось - это \"{0}\", использовано слов из фразы для данного слова {1}, всего для данного объекта - [2}.",
                                 dictWord, usedWords, allUsedWords);

                    // case when dict word has no links to objects
                    if (dictWord.getLinkerObjects().size() == 0)
                    {
                        outParsDebug(st1DebugLvl, engineDataHelper,
                                "Но у слова нет ссылок на объекты в истории -> ошибка \"Нигде не вижу\" с кол-вом слов {0}.",
                                     allUsedWords);
                        throw new IFML2ParseException(
                                MessageFormat.format("Нигде не вижу {0}.", dictWord.getFormByGramCase(Word.GramCaseEnum.RP)), allUsedWords);
                    }

                    if (fittedWords.contains(dictWord))
                    {
                        outParsDebug(st1DebugLvl, engineDataHelper, "Но это слово уже было ранее найдено для данного объекта -> ошибка.");
                        String usedPhrase = "";
                        for (String word : phrase.subList(0, allUsedWords - 1))
                        {
                            usedPhrase += " " + word;
                        }

                        throw new IFML2ParseException(
                                MessageFormat.format("Я бы понял фразу, если бы вы сказали \"{0}\"", usedPhrase.trim()), allUsedWords);
                    }

                    fittedWords.add(dictWord);
                    outParsDebug(st1DebugLvl, engineDataHelper, "Добавлем слово в список найденных, итого список: {0}.", fittedWords);

                    restPhrase = restPhrase.subList(usedWords, restPhrase.size());
                    outParsDebug(st1DebugLvl, engineDataHelper,
                            "Отрезаем от фразы часть, которая подошла по словам в кол-ве {0}, остаётся фраза: {1}.",
                                 usedWords, restPhrase);

                    wordIsFound = true;
                    break;
                }
            }

            if (!wordIsFound)
            {
                outParsDebug(st1DebugLvl, engineDataHelper, "Слово из фразы {0} не найдено во всём словаре.", restPhrase);
                if (fittedWords.size() > 0)
                {
                    outParsDebug(st1DebugLvl, engineDataHelper, "Но ранее найленные слова есть - {0} - завершение фазы I.", fittedWords);
                    break;
                }
                else
                {
                    String firstPhraseWord = restPhrase.get(0);
                    outParsDebug(st1DebugLvl, engineDataHelper,
                            "И нет найдыенных слов вообще -> ошибка - не знаем первое слово фразы \"{0}\" с кол-вом слов {1}.",
                                 firstPhraseWord, 1);
                    throw new IFML2ParseException(MessageFormat.format("Не знаю слово \"{0}\".", firstPhraseWord), 1);
                }
            }
        }

        // Stage II
        outParsDebug(debugLevel, engineDataHelper, "Фаза II: отсев слов, принадлежащих разным объектам...");
        int st2DebugLvl = debugLevel + 1;

        ArrayList<IFMLObject> objects = new ArrayList<IFMLObject>();
        objects.addAll(fittedWords.get(0).getLinkerObjects());
        outParsDebug(st2DebugLvl, engineDataHelper, "Добавляем в список объектов все объекты первого слова");

        if (fittedWords.size() == 1)
        {
            return new FitObjectWithPhraseResult(objects, allUsedWords);
        }

        for (Word word : fittedWords.subList(1, fittedWords.size()))
        {
            for (Iterator<IFMLObject> iterator = objects.iterator(); iterator.hasNext(); )
            {
                IFMLObject object = iterator.next();
                if (!word.getLinkerObjects().contains(object))
                {
                    if (objects.size() > 1)
                    {
                        iterator.remove();
                    }
                    else
                    {
                        throw new IFML2ParseException(MessageFormat.format("Не знаю такого предмета – \"{0}\"", fittedWords));
                    }
                }
            }
        }

        return new FitObjectWithPhraseResult(objects, allUsedWords);
    }

    private int fitWordWithPhrase(Word word, Word.GramCaseEnum gramCase, List<String> restPhrase)
    {
        String casedDictWord = word.getFormByGramCase(gramCase);
        List<String> casedDictWordArray = Arrays.asList(casedDictWord.split("\\s+"));

        if (casedDictWordArray.size() > 0 && casedDictWordArray.size() <= restPhrase.size())
        {
            int currentWord = 0;

            for (String dictWordPart : casedDictWordArray)
            {
                String phraseWord = restPhrase.get(currentWord);
                if (!dictWordPart.equalsIgnoreCase(phraseWord))
                {
                    return 0; // if at least one word isn't fit then don't accept it
                }
                currentWord++;
            }

            return currentWord;
        }

        return 0;
    }

    private int fitSynonymWithPhrase(String synonym, ArrayList<String> phrase, Engine.DataHelper engineDataHelper,
            int debugLevel) throws IFML2ParseException
    {
        outParsDebug(debugLevel, engineDataHelper, "Сравниваем синоним \"{0}\" с фразой {1}...", synonym, phrase);
        ArrayList<String> synonymWords = new ArrayList<String>(Arrays.asList(synonym.split("\\s+")));

        int synonymSize = synonymWords.size();

        // take length of shortest (synonym of phrase)
        int minLength = Math.min(synonymSize, phrase.size());
        outParsDebug(debugLevel, engineDataHelper, "Кол-во слов для проверки - {0}.", minLength);

        int usedWordsQty = 0; // fitted words for generating the most suitable exception

        for (int wordIdx = 0; wordIdx <= minLength - 1; wordIdx++)
        {
            int synWordDebugLvl = debugLevel + 1;
            String phraseWord = phrase.get(wordIdx);
            String synonymWord = synonymWords.get(wordIdx);
            outParsDebug(synWordDebugLvl, engineDataHelper, "Сравниваем слово фразы \"{0}\" со словом синонима \"{1}\"...", phraseWord,
                    synonymWord);
            if (!synonymWord.equalsIgnoreCase(phraseWord))
            {
                outParsDebug(synWordDebugLvl, engineDataHelper,
                        "Слова не равны -> ошибка сопоставления слова фразы \"{0}\" c кол-вом слов {1}.", phraseWord,
                             usedWordsQty);
                throw new IFML2ParseException("Не знаю, что такое \"" + phraseWord + "\".", usedWordsQty);
            }
            usedWordsQty++;
            outParsDebug(synWordDebugLvl, engineDataHelper, "Слово подошло, увеличиваем кол-во слов до {0} и идём дальше.", usedWordsQty);
        }
        outParsDebug(debugLevel, engineDataHelper, "Слова для сравнения кончились.");

        // check if synonym is not fully used
        if (usedWordsQty < synonymSize)
        {
            outParsDebug(debugLevel, engineDataHelper, "А синоним использован не весь -> ошибка с кол-вом слов {0}.", usedWordsQty);
            throw new IFML2ParseException("Команду не совсем понял, прошу уточнить.", usedWordsQty);
        }

        return usedWordsQty;
    }

    private String makeQuestionsForTemplate(ArrayList<TemplateElement> templateRest) throws IFML2Exception
    {
        String result = "";

        for (TemplateElement templateElement : templateRest)
        {
            if (templateElement instanceof LiteralTemplateElement)
            {
                result += ' ' + ((LiteralTemplateElement) templateElement).getSynonyms().get(0);
            }
            else if (templateElement instanceof ObjectTemplateElement)
            {
                result += ' ' + ((ObjectTemplateElement) templateElement).getGramCase().getQuestionWord();
            }
            else
            {
                throw new IFML2Exception("Системная ошибка: ЭлементШаблона неизвестного типа.");
            }
        }

        return CommonUtils.uppercaseFirstLetter(result.trim()) + "?";
    }

    public class ParseResult
    {
        public Action action = null;
        public List<FormalElement> formalElements = null;

        public ParseResult(Action action, ArrayList<FormalElement> formalElements)
        {
            this.action = action;
            this.formalElements = formalElements;
        }

        public Action getAction()
        {
            return action;
        }

        public List<FormalElement> getFormalElements()
        {
            return formalElements;
        }

        @Override
        public String toString()
        {
            return "Действие = [" + action +
                   "], формальные элементы = [" + formalElements + ']';
        }
    }

    private class TemplateElementFitResult
    {
        public FittedFormalElement fittedFormalElement = null;
        public int usedWordsQty = 0;

        public TemplateElementFitResult(FittedFormalElement fittedSynonym, int usedWordsQty)
        {
            this.fittedFormalElement = fittedSynonym;
            this.usedWordsQty = usedWordsQty;
        }

        @Override
        public String toString()
        {
            return "подошедший элемент = " + fittedFormalElement +
                   ", использовано слов = " + usedWordsQty;
        }
    }

    private class FittedTemplate
    {
        public Action action = null;
        public ArrayList<FittedFormalElement> fittedFormalElements = new ArrayList<FittedFormalElement>();

        public FittedTemplate(Action action, ArrayList<FittedFormalElement> fittedFormalElements)
        {
            this.action = action;
            this.fittedFormalElements = fittedFormalElements;
        }

        public ArrayList<FittedFormalElement> getFittedFormalElements()
        {
            return fittedFormalElements;
        }
    }

    protected class FittedFormalElement
    {
        String parameter = "";
    }

    private class FittedSynonym extends FittedFormalElement
    {
        String synonym = null;

        public FittedSynonym(String synonym, String parameter)
        {
            this.synonym = synonym;
            this.parameter = parameter;
        }

        @Override
        public String toString()
        {
            return synonym;
        }
    }

    private class FittedObjects extends FittedFormalElement
    {
        final Word.GramCaseEnum gramCase;
        ArrayList<IFMLObject> objects = new ArrayList<IFMLObject>();

        public FittedObjects(ArrayList<IFMLObject> objects, Word.GramCaseEnum gramCase, String parameter)
        {
            this.objects = objects;
            this.gramCase = gramCase;
            this.parameter = parameter;
        }

        public ArrayList<IFMLObject> getObjects()
        {
            return objects;
        }

        @Override
        public String toString()
        {
            return objects.get(0).toString();
        }
    }

    public class FitObjectWithPhraseResult
    {
        private final ArrayList<IFMLObject> objects;
        private final int usedWordsQty;

        public FitObjectWithPhraseResult(ArrayList<IFMLObject> objects, int usedWordsQty)
        {
            this.objects = objects;
            this.usedWordsQty = usedWordsQty;
        }

        public int getUsedWordsQty()
        {
            return usedWordsQty;
        }

        public ArrayList<IFMLObject> getObjects()
        {
            return objects;
        }
    }
}
