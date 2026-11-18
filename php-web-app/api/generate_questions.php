<?php
declare(strict_types=1);

header('Content-Type: application/json');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['error' => 'Method not allowed']);
    exit;
}

$apiKey = getenv('GEMINI_API_KEY') ?: '';
if ($apiKey === '') {
    http_response_code(500);
    echo json_encode(['error' => 'Server GEMINI_API_KEY environment variable is not configured.']);
    exit;
}

if (empty($_FILES['notes']['tmp_name'])) {
    http_response_code(400);
    echo json_encode(['error' => 'Please upload at least one study note image.']);
    exit;
}

$title = trim((string)($_POST['title'] ?? ''));
$difficulty = (string)($_POST['difficulty'] ?? 'Standard');
$mix = (string)($_POST['mix'] ?? '60_40');

$mixMap = [
    '60_40' => [60, 40],
    '70_30' => [70, 30],
    '50_50' => [50, 50],
];
[$targetObj, $targetTheory] = $mixMap[$mix] ?? [60, 40];

$imageParts = [];
$tmpNames = is_array($_FILES['notes']['tmp_name']) ? $_FILES['notes']['tmp_name'] : [$_FILES['notes']['tmp_name']];
$mimeTypes = is_array($_FILES['notes']['type']) ? $_FILES['notes']['type'] : [$_FILES['notes']['type']];

foreach ($tmpNames as $idx => $tmpPath) {
    if (!is_uploaded_file($tmpPath)) {
        continue;
    }
    $bytes = file_get_contents($tmpPath);
    if ($bytes === false) {
        continue;
    }
    $mime = $mimeTypes[$idx] ?? 'image/jpeg';
    $imageParts[] = [
        'inlineData' => [
            'mimeType' => $mime,
            'data' => base64_encode($bytes),
        ],
    ];
}

if (empty($imageParts)) {
    http_response_code(400);
    echo json_encode(['error' => 'No valid uploaded note images found.']);
    exit;
}

function callGeminiBatch(string $apiKey, array $imageParts, string $prompt): array {
    $url = 'https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent';
    $parts = array_merge($imageParts, [['text' => $prompt]]);
    $payload = [
        'contents' => [['parts' => $parts]],
        'generationConfig' => [
            'responseMimeType' => 'application/json',
            'temperature' => 0.5,
        ],
    ];

    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST => true,
        CURLOPT_HTTPHEADER => [
            'Content-Type: application/json',
            'x-goog-api-key: ' . $apiKey,
        ],
        CURLOPT_POSTFIELDS => json_encode($payload),
        CURLOPT_TIMEOUT => 60,
    ]);
    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $curlErr = curl_error($ch);
    curl_close($ch);

    if ($response === false || $httpCode >= 400) {
        throw new RuntimeException('Gemini request failed (' . $httpCode . '): ' . ($curlErr ?: $response));
    }

    $decoded = json_decode((string)$response, true);
    $text = $decoded['candidates'][0]['content']['parts'][0]['text'] ?? '';
    $json = json_decode($text, true);
    if (!is_array($json)) {
        throw new RuntimeException('Invalid JSON returned by model.');
    }
    return $json;
}

try {
    $allQuestions = [];
    $examTitle = $title !== '' ? $title : 'Study Notes Exam';
    $subject = 'General Studies';
    $overview = '';

    $objBatches = [intdiv($targetObj + 1, 2), $targetObj - intdiv($targetObj + 1, 2)];
    foreach ($objBatches as $bIdx => $count) {
        if ($count <= 0) continue;
        $prompt = "You are a teacher writing an exam from these student note photos. Difficulty: {$difficulty}. Return JSON {\"title\": string, \"subject\": string, \"teacherOverview\": string, \"questions\": [{\"questionType\": \"OBJECTIVE\", \"topicTag\": string, \"bloomLevel\": string, \"promptText\": string, \"options\": [4 strings], \"correctOptionIndex\": 0..3, \"explanation\": string}]}. Generate EXACTLY {$count} unique OBJECTIVE multiple-choice questions.";
        $res = callGeminiBatch($apiKey, $imageParts, $prompt);
        if ($bIdx === 0) {
            if ($title === '' && !empty($res['title'])) $examTitle = (string)$res['title'];
            if (!empty($res['subject'])) $subject = (string)$res['subject'];
            if (!empty($res['teacherOverview'])) $overview = (string)$res['teacherOverview'];
        }
        foreach (($res['questions'] ?? []) as $q) {
            $q['questionType'] = 'OBJECTIVE';
            $allQuestions[] = $q;
        }
    }

    $theoryBatches = [intdiv($targetTheory + 1, 2), $targetTheory - intdiv($targetTheory + 1, 2)];
    foreach ($theoryBatches as $count) {
        if ($count <= 0) continue;
        $prompt = "You are a teacher writing Section B (Theory & Essay) from these student note photos. Difficulty: {$difficulty}. Return JSON {\"questions\": [{\"questionType\": \"THEORY\", \"topicTag\": string, \"bloomLevel\": string, \"promptText\": string, \"marks\": integer, \"markingPoints\": [3..5 strings], \"modelAnswer\": string}]}. Generate EXACTLY {$count} unique THEORY questions.";
        $res = callGeminiBatch($apiKey, $imageParts, $prompt);
        foreach (($res['questions'] ?? []) as $q) {
            $q['questionType'] = 'THEORY';
            $allQuestions[] = $q;
        }
    }

    echo json_encode([
        'title' => $examTitle,
        'subject' => $subject,
        'difficulty' => $difficulty,
        'teacherOverview' => $overview,
        'questions' => array_slice($allQuestions, 0, 100),
    ]);
} catch (Throwable $e) {
    http_response_code(500);
    echo json_encode(['error' => $e->getMessage()]);
}
